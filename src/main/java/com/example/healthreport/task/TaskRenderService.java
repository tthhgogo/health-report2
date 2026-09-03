package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.render.CapacityPrecheckService;
import com.example.healthreport.render.ContentType;
import com.example.healthreport.render.FileToImageService;
import com.example.healthreport.render.FormatDetector;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.render.RenderableFile;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.Sha256Hex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 把一个任务绑定的全部文件读出来并统一转成全局图序列。
 * <p>按 {@code fileIndex} 升序处理——全局页码编址依赖这个顺序，数据库返回顺序不作数。</p>
 *
 * <p><b>文件字节用完即弃</b>：转图完成后不再持有任何原始文件字节，
 * 不缓存、不落盘、不进日志——一份 20MB 的原文件乘上并发任务数就是堆。</p>
 */
@Service
@Slf4j
public class TaskRenderService {

    private final CtHealthReportFileService fileService;
    private final S3FileStorage fileStorage;
    private final FileToImageService fileToImageService;
    private final FormatDetector formatDetector;
    private final CapacityPrecheckService capacityPrecheckService;

    public TaskRenderService(CtHealthReportFileService fileService, S3FileStorage fileStorage,
                             FileToImageService fileToImageService, FormatDetector formatDetector,
                             CapacityPrecheckService capacityPrecheckService) {
        this.fileService = fileService;
        this.fileStorage = fileStorage;
        this.fileToImageService = fileToImageService;
        this.formatDetector = formatDetector;
        this.capacityPrecheckService = capacityPrecheckService;
    }

    /** 读取并转图任务的全部文件；任一文件不可渲染即整任务失败，不跳过。 */
    public PageImageSequence renderFiles(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("任务标识不能为空");
        }
        List<CtHealthReportFileEntity> fileEntityList = fileService.findByTaskId(taskId);
        if (fileEntityList == null || fileEntityList.isEmpty()) {
            throw new IllegalStateException("任务没有关联文件");
        }
        List<CtHealthReportFileEntity> orderedList =
                new ArrayList<CtHealthReportFileEntity>(fileEntityList);
        for (CtHealthReportFileEntity fileEntity : orderedList) {
            assertBindingComplete(taskId, fileEntity);
        }
        Collections.sort(orderedList, new Comparator<CtHealthReportFileEntity>() {
            @Override
            public int compare(CtHealthReportFileEntity left, CtHealthReportFileEntity right) {
                return left.getFileIndex().compareTo(right.getFileIndex());
            }
        });
        assertTaskSnapshotValid(orderedList);

        log.info("任务原文件读取与转图开始，taskId={}，文件数={}", taskId, orderedList.size());
        // 惰性来源：字节由转图编排逐文件即时读取、渲染完即出作用域，
        // 5 份 × 20MB 的原文件绝不同时驻留内存（开发方案 §5.2）。
        List<RenderableFile> renderableList = new ArrayList<RenderableFile>(orderedList.size());
        for (final CtHealthReportFileEntity fileEntity : orderedList) {
            final ContentType contentType = contentTypeOf(fileEntity);
            renderableList.add(new RenderableFile() {
                @Override
                public int getFileIndex() {
                    return fileEntity.getFileIndex().intValue();
                }

                @Override
                public ContentType getContentType() {
                    return contentType;
                }

                @Override
                public byte[] readContentBytes() {
                    byte[] contentBytes = fileStorage.read(fileEntity.getCloudFileKey());
                    assertStoredContentValid(fileEntity, contentType, contentBytes);
                    log.info("任务原文件对象读取完成，taskId={}，fileIndex={}，字节数={}",
                            taskId, fileEntity.getFileIndex(), contentBytes.length);
                    return contentBytes;
                }
            });
        }
        PageImageSequence sequence = fileToImageService.render(renderableList);
        log.info("任务原文件读取与转图完成，taskId={}，文件数={}，总页数={}",
                taskId, orderedList.size(), sequence.size());
        return sequence;
    }

    /**
     * 断言这一行确实完成过绑定。
     *
     * <p>{@code file_index} 在 DDL 里是 <b>NULL</b> 列（上传时置空、绑定时才写），而本查询只按
     * {@code task_id} 过滤——出现「有 task_id 却没有 file_index」的行时，直接拆箱是一个裸 NPE，
     * 崩得没有信息。这里换成带列名的显式失败，排障时一眼能定位（开发方案 §0.3.1）。</p>
     */
    private void assertBindingComplete(String taskId, CtHealthReportFileEntity fileEntity) {
        if (fileEntity == null) {
            throw new IllegalStateException("任务文件查询返回空行");
        }
        if (!taskId.equals(fileEntity.getTaskId())
                || !FileStatus.UPLOADED.name().equals(fileEntity.getStatus())
                || fileEntity.getFileId() == null || fileEntity.getFileId().isEmpty()
                || fileEntity.getFileIndex() == null
                || fileEntity.getContentType() == null || fileEntity.getContentType().isEmpty()
                || fileEntity.getSizeBytes() == null || fileEntity.getSizeBytes().longValue() <= 0L
                || fileEntity.getPrecheckPages() == null || fileEntity.getPrecheckPages().intValue() <= 0
                || fileEntity.getContentHash() == null || fileEntity.getContentHash().isEmpty()
                || fileEntity.getCloudFileKey() == null || fileEntity.getCloudFileKey().isEmpty()) {
            throw new IllegalStateException("文件行绑定不完整，fileId=" + fileEntity.getFileId()
                    + "，fileIndex=" + fileEntity.getFileIndex()
                    + "，precheckPages=" + fileEntity.getPrecheckPages());
        }
    }

    /**
     * 复核已经创建成功的任务快照仍满足文件数、连续序号、总字节数与总页数上限。
     * <p>这些限制在创建任务时已经裁决；此处命中说明数据库内容发生漂移，按服务端故障失败，
     * 不能重新归因成用户上传错误。</p>
     */
    private void assertTaskSnapshotValid(List<CtHealthReportFileEntity> orderedList) {
        if (orderedList.size() > FileBindingService.MAX_FILE_COUNT) {
            throw new IllegalStateException("任务文件数超过创建时上限");
        }
        long totalBytes = 0L;
        int totalPages = 0;
        for (int index = 0; index < orderedList.size(); index++) {
            CtHealthReportFileEntity fileEntity = orderedList.get(index);
            if (fileEntity.getFileIndex().intValue() != index) {
                throw new IllegalStateException("任务文件序号不连续，期望=" + index
                        + "，实际=" + fileEntity.getFileIndex());
            }
            if (fileEntity.getSizeBytes().longValue() > FileBindingService.MAX_TOTAL_BYTES - totalBytes) {
                throw new IllegalStateException("任务文件总字节数超过创建时上限");
            }
            if (fileEntity.getPrecheckPages().intValue()
                    > FileBindingService.MAX_PRECHECK_PAGES - totalPages) {
                throw new IllegalStateException("任务总页数超过创建时上限");
            }
            totalBytes += fileEntity.getSizeBytes().longValue();
            totalPages += fileEntity.getPrecheckPages().intValue();
        }
    }

    /**
     * 对对象存储读回的字节执行完整的第二道边界校验。
     * <p>长度与哈希防止对象被覆盖或损坏；重新识别格式会同时重跑 OFD ZIP 炸弹扫描；
     * 可读性和精确页数预检则防止数据库元数据漂移。任何不一致都是服务端完整性故障。</p>
     */
    private void assertStoredContentValid(CtHealthReportFileEntity fileEntity, ContentType recordedContentType,
                                          byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw integrityFailure(fileEntity, "对象存储返回空内容", null);
        }
        if (contentBytes.length != fileEntity.getSizeBytes().longValue()) {
            throw integrityFailure(fileEntity, "对象内容长度与上传记录不一致", null);
        }
        if (!Sha256Hex.of(contentBytes).equals(fileEntity.getContentHash())) {
            throw integrityFailure(fileEntity, "对象内容哈希与上传记录不一致", null);
        }

        final ContentType detectedContentType;
        try {
            detectedContentType = formatDetector.detect(contentBytes);
        } catch (HealthReportException exception) {
            throw integrityFailure(fileEntity, "对象内容未通过渲染前安全预检", exception);
        }
        if (detectedContentType != recordedContentType) {
            throw integrityFailure(fileEntity, "对象真实格式与上传记录不一致", null);
        }
        long perFileMaxBytes = detectedContentType == ContentType.JPG || detectedContentType == ContentType.PNG
                ? FileUploadService.PRODUCT_IMAGE_MAX_BYTES : FileUploadService.DOCUMENT_MAX_BYTES;
        if ((long) contentBytes.length > perFileMaxBytes) {
            throw integrityFailure(fileEntity, "对象字节数超过上传格式上限", null);
        }
        final int detectedPages;
        try {
            detectedPages = capacityPrecheckService.precheckPages(contentBytes, detectedContentType);
        } catch (HealthReportException exception) {
            throw integrityFailure(fileEntity, "对象内容未通过渲染前安全预检", exception);
        }
        if (detectedPages != fileEntity.getPrecheckPages().intValue()) {
            throw integrityFailure(fileEntity, "对象精确页数与上传记录不一致", null);
        }
    }

    /** 生成不携带文件正文的完整性异常；Worker 会统一映射为可重试的 SERVER_ERROR。 */
    private IllegalStateException integrityFailure(CtHealthReportFileEntity fileEntity, String reason,
                                                   RuntimeException cause) {
        String message = reason + "，fileIndex=" + fileEntity.getFileIndex()
                + "，cloudFileKey=" + fileEntity.getCloudFileKey();
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    /** 库里存的是格式判定时写入的枚举名；对不上说明数据被改过，按服务端完整性故障失败。 */
    private ContentType contentTypeOf(CtHealthReportFileEntity fileEntity) {
        try {
            return ContentType.valueOf(fileEntity.getContentType());
        } catch (IllegalArgumentException exception) {
            throw integrityFailure(fileEntity, "文件行内容格式枚举无效", exception);
        }
    }
}
