package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.FileParseService;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 把一个任务绑定的全部文件读出来并逐个解析。
 * <p>按 {@code fileIndex} 升序处理，因为 {@code segmentId} 的 {@code f{fileIndex}}
 * 与批次编址都依赖这个顺序；数据库返回顺序不作数。</p>
 *
 * <p><b>文件字节用完即弃</b>：每个文件解析完就不再持有它的原始字节，
 * 不缓存、不落盘、不进日志——一份 20MB 的原文件乘上并发任务数就是堆。</p>
 */
@Service
@Slf4j
public class TaskParseService {

    private final CtHealthReportFileService fileService;
    private final S3FileStorage fileStorage;
    private final FileParseService fileParseService;

    public TaskParseService(CtHealthReportFileService fileService, S3FileStorage fileStorage,
                            FileParseService fileParseService) {
        this.fileService = fileService;
        this.fileStorage = fileStorage;
        this.fileParseService = fileParseService;
    }

    /** 读取并解析任务的全部文件；任一文件不可读即整任务失败，不跳过。 */
    public List<ParsedFile> parseFiles(String taskId) {
        List<CtHealthReportFileEntity> fileEntityList = fileService.findByTaskId(taskId);
        if (fileEntityList == null || fileEntityList.isEmpty()) {
            throw new HealthReportException(FailCode.UNREADABLE, 400);
        }
        List<CtHealthReportFileEntity> orderedList =
                new ArrayList<CtHealthReportFileEntity>(fileEntityList);
        for (CtHealthReportFileEntity fileEntity : orderedList) {
            assertBindingComplete(fileEntity);
        }
        Collections.sort(orderedList, new Comparator<CtHealthReportFileEntity>() {
            @Override
            public int compare(CtHealthReportFileEntity left, CtHealthReportFileEntity right) {
                return left.getFileIndex().compareTo(right.getFileIndex());
            }
        });

        log.info("任务原文件读取与解析开始，taskId={}，文件数={}", taskId, orderedList.size());
        List<ParsedFile> parsedFileList = new ArrayList<ParsedFile>(orderedList.size());
        for (CtHealthReportFileEntity fileEntity : orderedList) {
            byte[] contentBytes = fileStorage.read(fileEntity.getCloudFileKey());
            log.info("任务原文件对象读取完成，taskId={}，fileIndex={}，字节数={}",
                    taskId, fileEntity.getFileIndex(), contentBytes.length);
            parsedFileList.add(fileParseService.parse(
                    fileEntity.getFileIndex().intValue(),
                    contentTypeOf(fileEntity),
                    contentBytes,
                    fileEntity.getPrecheckPages().intValue()));
        }
        log.info("任务原文件读取与解析完成，taskId={}，文件数={}",
                taskId, parsedFileList.size());
        return parsedFileList;
    }

    /**
     * 断言这一行确实完成过绑定。
     *
     * <p>{@code file_index} 在 DDL 里是 <b>NULL</b> 的：上传时显式置空
     * （{@code FileUploadService.buildFileEntity}），绑定时才写入。而本查询只按
     * {@code task_id} 过滤，<b>不校验 file_index</b>——一旦出现「有 task_id 却没有
     * file_index」的行（绑定实现出 bug、或有人手工改库），下面的拆箱就是一个裸 NPE。</p>
     *
     * <p>裸 NPE 的问题不是它会崩，是它<b>崩得没有信息</b>：Worker 兜底成 SERVER_ERROR，
     * 日志里只有一行 NullPointerException，看不出是哪一列、哪一行、为什么。
     * 这里换成带列名的显式失败，排障时一眼能定位。</p>
     */
    private void assertBindingComplete(CtHealthReportFileEntity fileEntity) {
        if (fileEntity.getFileIndex() == null || fileEntity.getPrecheckPages() == null) {
            throw new IllegalStateException("文件行绑定不完整，fileId=" + fileEntity.getFileId()
                    + "，fileIndex=" + fileEntity.getFileIndex()
                    + "，precheckPages=" + fileEntity.getPrecheckPages());
        }
    }

    /** 库里存的是格式判定时写入的枚举名；对不上说明数据被改过，按不支持的格式失败。 */
    private ContentType contentTypeOf(CtHealthReportFileEntity fileEntity) {
        try {
            return ContentType.valueOf(fileEntity.getContentType());
        } catch (IllegalArgumentException exception) {
            throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400, exception);
        }
    }
}
