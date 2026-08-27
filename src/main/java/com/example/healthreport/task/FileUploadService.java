package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.parse.CapacityPrecheckService;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.FormatDetector;
import com.example.healthreport.parse.OcrProperties;
import com.example.healthreport.parse.ReadabilityChecker;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.IdCanonicalizer;
import com.example.healthreport.support.SensitiveLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 单文件上传入口：完整校验后写对象存储并落文件元数据。
 */
@Slf4j
@Service
public class FileUploadService {

    static final long DOCUMENT_MAX_BYTES = 20L * 1024L * 1024L;
    static final long PRODUCT_IMAGE_MAX_BYTES = 10L * 1024L * 1024L;

    private final FormatDetector formatDetector;
    private final ReadabilityChecker readabilityChecker;
    private final CapacityPrecheckService capacityPrecheckService;
    private final CtHealthReportFileService fileService;
    private final S3FileStorage fileStorage;
    private final IdCanonicalizer idCanonicalizer;
    private final OcrProperties ocrProperties;
    private final Clock clock;

    @Autowired
    public FileUploadService(FormatDetector formatDetector,
                             ReadabilityChecker readabilityChecker,
                             CapacityPrecheckService capacityPrecheckService,
                             CtHealthReportFileService fileService,
                             S3FileStorage fileStorage,
                             IdCanonicalizer idCanonicalizer,
                             OcrProperties ocrProperties) {
        this(formatDetector, readabilityChecker, capacityPrecheckService, fileService, fileStorage,
                idCanonicalizer, ocrProperties, Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造器，仅用于确定性测试。 */
    public FileUploadService(FormatDetector formatDetector,
                             ReadabilityChecker readabilityChecker,
                             CapacityPrecheckService capacityPrecheckService,
                             CtHealthReportFileService fileService,
                             S3FileStorage fileStorage,
                             IdCanonicalizer idCanonicalizer,
                             OcrProperties ocrProperties,
                             Clock clock) {
        this.formatDetector = formatDetector;
        this.readabilityChecker = readabilityChecker;
        this.capacityPrecheckService = capacityPrecheckService;
        this.fileService = fileService;
        this.fileStorage = fileStorage;
        this.idCanonicalizer = idCanonicalizer;
        this.ocrProperties = ocrProperties;
        this.clock = clock;
    }

    /**
     * 上传一个文件并返回 fileId。
     * <p>所有格式、可读性与容量校验均早于对象存储和数据库写入。</p>
     */
    public String upload(MultipartFile multipartFile, String userId) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
        }
        if (multipartFile.getSize() > DOCUMENT_MAX_BYTES) {
            throw new HealthReportException(FailCode.FILE_TOO_LARGE, 400);
        }

        byte[] contentBytes = readBytes(multipartFile);
        ContentType contentType = formatDetector.detect(contentBytes);
        assertByteLimit(contentBytes.length, contentType);
        readabilityChecker.check(contentBytes, contentType);
        int precheckPages = capacityPrecheckService.precheckPages(contentBytes, contentType);

        String fileId = idCanonicalizer.newFileId();
        String objectKey = "health-report/" + fileId;
        CtHealthReportFileEntity fileEntity = buildFileEntity(multipartFile, userId, contentBytes,
                contentType, precheckPages, fileId, objectKey);

        // ⛔ 顺序不能反：【先插库行，再写对象】。
        //
        // 反过来（先写对象）时，只要在两步之间失败，就会留下一个【数据库里没有任何行指向它】
        // 的 S3 对象——而孤儿清理是从 file 行出发去找对象的（selectExpiredOrphans），
        // 没有行就永远发现不了它。那个对象里是一份体检报告原文，会无限期残留。
        //
        // 而且这不需要「插库失败 + 补偿删除也失败」两次故障：进程在两步之间被杀
        // （OOM kill、滚动发布、kill -9）时补偿代码根本不会执行，【一次中断就够】。
        //
        // 现在这个顺序下，file 行【就是账本】：任何一步失败都留下一行可见、无害、
        // 指向可能不存在的对象的记录，30 分钟后由现有的孤儿清理统一处理。
        try {
            if (fileService.insertFromApi(fileEntity) != 1) {
                throw new HealthReportException(FailCode.SERVER_ERROR, 500);
            }
        } catch (HealthReportException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 行都没插进去，没有对象需要清理。
            log.error("上传文件元数据写入失败，fileId={}", fileId, sanitizedException(exception));
            throw new HealthReportException(FailCode.SERVER_ERROR, 500);
        }

        try {
            fileStorage.write(objectKey, contentBytes);
            // 上传成功是用户能感知到的第一个节点，必须留痕：出问题时先看这条在不在，
            // 就能把「根本没传上去」和「传上去了但后面分析失败」分开，不用去翻对象存储。
            // 【不带 origin_name】它常含姓名与体检属性（§9.2 红线），只走 SensitiveLog。
            log.info("文件上传成功，fileId={}，格式={}，字节数={}，预检等效页数={}",
                    fileId, contentType, contentBytes.length, precheckPages);
            SensitiveLog.debug("文件上传成功的原始文件名，fileId={}，originName={}",
                    fileId, fileEntity.getOriginName());
            return fileId;
        } catch (RuntimeException exception) {
            // ⛔ 【不要在这里删除 file 行】。写对象失败可能是假阴性——超时的 PUT 在服务端
            // 可能已经成功。删掉行就回到了「对象存在但无人指向」的原问题。
            // 保留行，让孤儿清理无条件去删一次对象，这正是账本存在的意义。
            log.error("上传文件对象写入失败，保留 file 行交由孤儿清理，fileId={}",
                    fileId, sanitizedException(exception));
            throw new HealthReportException(FailCode.SERVER_ERROR, 500);
        }
    }

    private byte[] readBytes(MultipartFile multipartFile) {
        try {
            return multipartFile.getBytes();
        } catch (IOException exception) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400, exception);
        }
    }

    private void assertByteLimit(int contentLength, ContentType contentType) {
        long maxBytes = DOCUMENT_MAX_BYTES;
        if (contentType == ContentType.JPG || contentType == ContentType.PNG) {
            long effectiveOcrImageBytes = ocrProperties.getEffectiveOcrImageBytes();
            if (effectiveOcrImageBytes <= 0L) {
                throw new HealthReportException(FailCode.SERVER_ERROR, 500);
            }
            maxBytes = Math.min(PRODUCT_IMAGE_MAX_BYTES, effectiveOcrImageBytes);
        }
        if ((long) contentLength > maxBytes) {
            throw new HealthReportException(FailCode.FILE_TOO_LARGE, 400);
        }
    }

    private CtHealthReportFileEntity buildFileEntity(MultipartFile multipartFile, String userId,
                                                       byte[] contentBytes, ContentType contentType,
                                                       int precheckPages, String fileId, String objectKey) {
        CtHealthReportFileEntity fileEntity = new CtHealthReportFileEntity();
        fileEntity.setFileId(fileId);
        fileEntity.setUserId(userId);
        fileEntity.setTaskId(null);
        fileEntity.setFileIndex(null);
        fileEntity.setStatus(FileStatus.UPLOADED.name());
        String originalFilename = multipartFile.getOriginalFilename();
        fileEntity.setOriginName(originalFilename == null ? "" : originalFilename);
        fileEntity.setContentType(contentType.name());
        fileEntity.setSizeBytes((long) contentBytes.length);
        fileEntity.setPrecheckPages(precheckPages);
        fileEntity.setContentHash(sha256(contentBytes));
        fileEntity.setCloudFileKey(objectKey);
        fileEntity.setExpireAt(LocalDateTime.now(clock).plusMinutes(30L));
        return fileEntity;
    }

    private String sha256(byte[] contentBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(contentBytes);
            StringBuilder result = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                result.append(String.format("%02x", hashByte & 0xFF));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }

    /**
     * 外部异常可能携带敏感参数；日志只保留类型和不含业务数据的原始调用栈。
     */
    private IllegalStateException sanitizedException(RuntimeException exception) {
        IllegalStateException sanitizedException = new IllegalStateException(
                "外部调用异常类型:" + exception.getClass().getName());
        sanitizedException.setStackTrace(exception.getStackTrace());
        return sanitizedException;
    }
}
