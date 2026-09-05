package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.render.CapacityPrecheckService;
import com.example.healthreport.render.ContentType;
import com.example.healthreport.render.FormatDetector;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.IdCanonicalizer;
import com.example.healthreport.support.Sha256Hex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 单文件上传入口：完整校验后写对象存储并落文件元数据。
 */
@Slf4j
@Service
public class FileUploadService {

	/** PDF/OFD 单文件 20MiB 上限，来自设计方案 §3.1 产品容量口径。 */
	static final long DOCUMENT_MAX_BYTES = 20L * 1024L * 1024L;

	/** JPG/PNG 单文件 10MiB 上限，来自设计方案 §3.1 产品容量口径。 */
	static final long PRODUCT_IMAGE_MAX_BYTES = 10L * 1024L * 1024L;

	private final FormatDetector formatDetector;

	private final CapacityPrecheckService capacityPrecheckService;

	private final CtHealthReportFileService fileService;

	private final S3FileStorage fileStorage;

	private final IdCanonicalizer idCanonicalizer;

	private final Clock clock;

	@Autowired
	public FileUploadService(FormatDetector formatDetector,
			CapacityPrecheckService capacityPrecheckService, CtHealthReportFileService fileService,
			S3FileStorage fileStorage, IdCanonicalizer idCanonicalizer) {
		this(formatDetector, capacityPrecheckService, fileService, fileStorage, idCanonicalizer,
				Clock.systemDefaultZone());
	}

	/** 可注入时钟的构造器，仅用于确定性测试。 */
	public FileUploadService(FormatDetector formatDetector,
			CapacityPrecheckService capacityPrecheckService, CtHealthReportFileService fileService,
			S3FileStorage fileStorage, IdCanonicalizer idCanonicalizer, Clock clock) {
		this.formatDetector = formatDetector;
		this.capacityPrecheckService = capacityPrecheckService;
		this.fileService = fileService;
		this.fileStorage = fileStorage;
		this.idCanonicalizer = idCanonicalizer;
		this.clock = clock;
	}

	/**
	 * 上传一个文件并返回 fileId。
	 * <p>
	 * 所有格式、可读性与容量校验均早于对象存储和数据库写入。
	 * </p>
	 */
	public String upload(MultipartFile multipartFile, String userId, String companyId) {
		OwnerContext.assertValid(userId, companyId);
		if (multipartFile == null || multipartFile.isEmpty()) {
			throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
		}
		if (multipartFile.getSize() > DOCUMENT_MAX_BYTES) {
			throw new HealthReportException(FailCode.FILE_TOO_LARGE, 400);
		}

		byte[] contentBytes = readBytes(multipartFile);
		ContentType contentType = formatDetector.detect(contentBytes);
		assertByteLimit(contentBytes.length, contentType);
		// 可读性与页数预检一次解析同时完成（CapacityPrecheckService，前置条件是上面的 detect）。
		int precheckPages = capacityPrecheckService.precheckPages(contentBytes, contentType);

		String fileId = idCanonicalizer.newFileId();
		String objectKey = "health-report/" + fileId;
		CtHealthReportFileEntity fileEntity = buildFileEntity(userId, companyId, contentBytes,
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
		}
		catch (HealthReportException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			// 行都没插进去，没有对象需要清理。
			log.error("上传文件元数据写入失败，fileId={}", fileId, sanitizedException(exception));
			throw new HealthReportException(FailCode.SERVER_ERROR, 500);
		}

		try {
			fileStorage.write(objectKey, contentBytes);
			// 上传成功是用户能感知到的第一个节点，必须留痕：出问题时先看这条在不在，
			// 就能把「根本没传上去」和「传上去了但后面分析失败」分开，不用去翻对象存储。
			log.info("文件上传成功，fileId={}，格式={}，字节数={}，预检等效页数={}", fileId, contentType, contentBytes.length,
					precheckPages);
			return fileId;
		}
		catch (RuntimeException exception) {
			// ⛔ 【不要在这里删除 file 行】。写对象失败可能是假阴性——超时的 PUT 在服务端
			// 可能已经成功。删掉行就回到了「对象存在但无人指向」的原问题。
			// 保留行，让孤儿清理无条件去删一次对象，这正是账本存在的意义。
			log.error("上传文件对象写入失败，保留 file 行交由孤儿清理，fileId={}", fileId, sanitizedException(exception));
			throw new HealthReportException(FailCode.SERVER_ERROR, 500);
		}
	}

	private byte[] readBytes(MultipartFile multipartFile) {
		try {
			return multipartFile.getBytes();
		}
		catch (IOException exception) {
			throw new HealthReportException(FailCode.FILE_UNREADABLE, 400, exception);
		}
	}

	private void assertByteLimit(int contentLength, ContentType contentType) {
		// 图片上传采用产品口径；模型请求体约束由压缩器（单页 ≤1MiB）与客户端上限承担，
		// 上传原始字节数与请求体积已经解耦，不再反推。
		long maxBytes = contentType == ContentType.JPG || contentType == ContentType.PNG
				? PRODUCT_IMAGE_MAX_BYTES : DOCUMENT_MAX_BYTES;
		if ((long) contentLength > maxBytes) {
			throw new HealthReportException(FailCode.FILE_TOO_LARGE, 400);
		}
	}

	private CtHealthReportFileEntity buildFileEntity(String userId, String companyId,
			byte[] contentBytes, ContentType contentType, int precheckPages, String fileId, String objectKey) {
		CtHealthReportFileEntity fileEntity = new CtHealthReportFileEntity();
		fileEntity.setFileId(fileId);
		fileEntity.setCompanyId(companyId);
		fileEntity.setUserId(userId);
		fileEntity.setTaskId(null);
		fileEntity.setFileIndex(null);
		fileEntity.setStatus(FileStatus.UPLOADED.name());
		fileEntity.setDisplayName(displayName(fileId, contentType));
		fileEntity.setContentType(contentType.name());
		fileEntity.setSizeBytes((long) contentBytes.length);
		fileEntity.setPrecheckPages(precheckPages);
		fileEntity.setContentHash(Sha256Hex.of(contentBytes));
		fileEntity.setCloudFileKey(objectKey);
		fileEntity.setExpireAt(LocalDateTime.now(clock).plusMinutes(30L));
		return fileEntity;
	}

	/**
	 * 展示名完全由服务端生成，不含任何用户输入；原始文件名（常含姓名与体检属性）从不落任何存储。
	 * <p>扩展名由内容判定的真实格式映射，不信任用户扩展名；fileId 前缀保证同任务多文件可区分且幂等。</p>
	 */
	private String displayName(String fileId, ContentType contentType) {
		return "体检报告-" + fileId.substring(0, 8) + "." + contentType.name().toLowerCase(Locale.ROOT);
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
