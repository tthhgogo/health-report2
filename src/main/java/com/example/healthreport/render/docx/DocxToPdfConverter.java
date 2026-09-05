package com.example.healthreport.render.docx;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.extern.slf4j.Slf4j;
import org.docx4j.Docx4J;
import org.docx4j.convert.out.FOSettings;
import org.docx4j.fonts.IdentityPlusMapper;
import org.docx4j.fonts.PhysicalFont;
import org.docx4j.fonts.PhysicalFonts;
import org.docx4j.model.images.ConversionImageHandler;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * DOCX 排版转 PDF（docx4j → XSL-FO/Apache FOP），是 DOCX 接入图像链路的唯一入口
 * （设计方案 §3.2.1，2026-09-05 恢复 DOCX 支持）。
 *
 * <p><b>页数以本转换结果为准</b>：DOCX 没有固有分页，docx4j 对同一份字节、同一字体环境的
 * 排版是确定性的，因此上传预检与 Worker 复核各转一次得到的页数必然一致，
 * 「precheck_pages 恒为精确页数」的既有契约（§3.4.1）得以保持，无需恢复旧的二次容量裁决。</p>
 *
 * <p><b>图片一律丢弃</b>：无论内嵌还是外链，转换产物不包含任何图片——内嵌医学影像不落盘
 * （不产生临时文件，脱离任务生命周期的残留无从谈起），外链图片不触发任何网络请求（SSRF 与
 * 确定性破坏都被掐断）。业务上模块输出只依赖文字与表格，图片本就不参与后续判断。
 * 实现是<b>元素级连根移除</b>（{@link #stripAllImages}）而非仅替换图片处理器：docx4j 计算
 * 页眉/页脚高度时会另建转换配置，不继承外部设置的处理器，会走回默认的外链读取与
 * 临时文件落盘逻辑——处理器只能护住正文，护不住页眉页脚。</p>
 *
 * <p><b>字体环境是保真的硬前提</b>：文档声明的中文字体（宋体、等线、Noto 系等）必须映射到
 * 实际存在的 CJK 物理字体，否则 FOP 把全部中文渲染成 #。应用内置思源黑体
 * （{@code fonts/SourceHanSansCN-Regular.otf}，SIL OFL 1.1，许可证同目录随包分发），
 * 首次转换时解压注册、<b>优先于系统字体</b>——排版环境随代码走，跨机器分页一致，
 * 部署镜像无需安装字体；内置字体加载失败时退回系统字体候选表
 * {@link #CJK_TARGET_CANDIDATES}，两者都不可用才按 SERVER_ERROR 失败
 * ——环境问题不能归因为用户文件不可读。字体文件的 SHA-256 由契约测试钉死，
 * 换字体等于换排版结果，必须显式过评审。</p>
 */
@Slf4j
@Component
public class DocxToPdfConverter {

	/**
	 * 物理字体扫描白名单：只扫描映射会用到的字体，双重目的——避免个别异形系统字体让
	 * FOP 的字体表解析崩溃，也把全盘字体扫描压到秒级。
	 */
	private static final String PHYSICAL_FONT_SCAN_REGEX = ".*(?i)(Arial|Times|Courier|Calibri|Cambria|"
			+ "Songti|STSong|STHeiti|STFangsong|STKaiti|PingFang|SimSun|SimHei|KaiTi|FangSong|DengXian|"
			+ "Microsoft YaHei|Noto Sans CJK|Noto Serif CJK|Source Han|WenQuanYi).*";

	/** 内置字体的 classpath 资源路径与注册后的字族名。 */
	static final String BUNDLED_FONT_RESOURCE = "/fonts/SourceHanSansCN-Regular.otf";

	static final String BUNDLED_FONT_FAMILY = "Source Han Sans CN";

	/** CJK 替换目标字体候选（系统字体兜底），按优先级排列；正常情况下内置字体已命中，不会走到这里。 */
	static final String[] CJK_TARGET_CANDIDATES = { "Noto Sans CJK SC", "Noto Serif CJK SC",
			"Source Han Sans SC", "Source Han Serif SC", "SimSun", "Microsoft YaHei", "SimHei",
			"Arial Unicode MS", "Songti SC", "STSong", "PingFang SC", "WenQuanYi Zen Hei",
			"WenQuanYi Micro Hei" };

	/** 体检报告 DOCX 里常见的中文字体声明名，全部映射到同一个替换目标。 */
	private static final String[] DECLARED_CJK_FONT_NAMES = { "宋体", "黑体", "楷体", "仿宋", "等线",
			"等线 Light", "微软雅黑", "新宋体", "华文宋体", "苹方", "SimSun", "NSimSun", "SimHei", "KaiTi",
			"FangSong", "DengXian", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
			"Noto Serif CJK SC", "Source Han Sans SC", "Source Han Serif SC", "ＭＳ 明朝", "ＭＳ ゴシック",
			"MS Mincho", "MS Gothic" };

	/**
	 * 丢弃全部图片的处理器：返回 null 让排版跳过图片元素。既不落盘（默认的
	 * FileConversionImageHandler 会把内嵌图片写进 java.io.tmpdir 且无人清理），
	 * 也绝不解析外链目标（外链图片是服务端 URL 读取的入口）。无状态，可安全共享。
	 * <p>仅作 {@link #stripAllImages} 之后的纵深防线：主转换配置走它，
	 * 但页眉/页脚测高的内部配置不继承它，不能只靠这一层。</p>
	 */
	private static final ConversionImageHandler DROP_ALL_IMAGES =
			(picture, relationship, binaryPart) -> null;

	/**
	 * 把包内所有图片元素连根移除：正文、页眉、页脚、脚注、尾注、批注里的
	 * DrawingML（w:drawing）、VML（w:pict）与 OLE 预览（w:object）。
	 * 元素删掉后，docx4j 内部任何转换配置都无图可取——外链无从访问，临时文件无从产生。
	 */
	private static void stripAllImages(WordprocessingMLPackage wordPackage) {
		for (org.docx4j.openpackaging.parts.Part part
				: wordPackage.getParts().getParts().values()) {
			if (!(part instanceof org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart
					|| part instanceof org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart
					|| part instanceof org.docx4j.openpackaging.parts.WordprocessingML.FooterPart
					|| part instanceof org.docx4j.openpackaging.parts.WordprocessingML.FootnotesPart
					|| part instanceof org.docx4j.openpackaging.parts.WordprocessingML.EndnotesPart
					|| part instanceof org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart)) {
				continue;
			}
			final Object partRoot;
			try {
				partRoot = ((org.docx4j.openpackaging.parts.JaxbXmlPart<?>) part).getContents();
			}
			catch (Exception exception) {
				// 部件内容取不出来的坏文件交给后续转换按「不可读」失败，这里不吞整体异常。
				continue;
			}
			if (partRoot == null) {
				continue;
			}
			final java.util.List<org.docx4j.wml.R> runList =
					new java.util.ArrayList<org.docx4j.wml.R>();
			new org.docx4j.TraversalUtil(partRoot, new org.docx4j.TraversalUtil.CallbackImpl() {
				@Override
				public java.util.List<Object> apply(Object object) {
					if (object instanceof org.docx4j.wml.R) {
						runList.add((org.docx4j.wml.R) object);
					}
					return null;
				}
			});
			for (org.docx4j.wml.R run : runList) {
				java.util.Iterator<Object> contentIterator = run.getContent().iterator();
				while (contentIterator.hasNext()) {
					Object value = org.docx4j.XmlUtils.unwrap(contentIterator.next());
					if (value instanceof org.docx4j.wml.Drawing
							|| value instanceof org.docx4j.wml.Pict
							|| value instanceof org.docx4j.wml.CTObject) {
						contentIterator.remove();
					}
				}
			}
		}
	}

	private static final Object FONT_ENVIRONMENT_LOCK = new Object();

	private static volatile PhysicalFont cjkTargetFont;

	private static volatile boolean fontEnvironmentInitialized;

	/**
	 * 把一份 DOCX 排版转换为 PDF 字节。
	 *
	 * @throws IOException 文档损坏或排版失败；调用方按各自阶段映射为
	 *             FILE_UNREADABLE（上传）或 UNREADABLE（Worker）
	 * @throws HealthReportException SERVER_ERROR：本机无任何可用 CJK 物理字体（部署环境问题）
	 */
	public byte[] toPdf(byte[] docxBytes) throws IOException {
		if (docxBytes == null || docxBytes.length == 0) {
			throw new IOException("DOCX 内容为空");
		}
		final WordprocessingMLPackage wordPackage;
		try {
			wordPackage = WordprocessingMLPackage.load(new ByteArrayInputStream(docxBytes));
		}
		catch (Exception exception) {
			throw new IOException("DOCX 文档无法解析", exception);
		}
		// 字体解析放在文档加载之后：坏文件在无字体的机器上也应报「不可读」而非环境错误。
		PhysicalFont targetFont = resolveCjkTargetFont();
		try {
			stripAllImages(wordPackage);
			// get() 永不返回 null：候选表之外的声明字体（华文仿宋等）与文档默认字体全部
			// 兜底到目标 CJK 字体。RunFontSelector 只认 get() 的结果，未映射字体名会原样
			// 传给 FOP 并静默退回 Base14，中文整段渲染成 #（Mapper.FONT_FALLBACK 在
			// 本 FO 导出路径不被消费，帮不上忙）。
			IdentityPlusMapper fontMapper = new IdentityPlusMapper() {
				@Override
				public PhysicalFont get(String fontName) {
					PhysicalFont mapped = super.get(fontName);
					return mapped != null ? mapped : targetFont;
				}
			};
			for (String declaredFontName : DECLARED_CJK_FONT_NAMES) {
				fontMapper.put(declaredFontName, targetFont);
			}
			wordPackage.setFontMapper(fontMapper);
			FOSettings foSettings = new FOSettings(wordPackage);
			foSettings.setApacheFopMime(FOSettings.MIME_PDF);
			foSettings.setImageHandler(DROP_ALL_IMAGES);
			ByteArrayOutputStream pdfStream = new ByteArrayOutputStream();
			Docx4J.toFO(foSettings, pdfStream, Docx4J.FLAG_EXPORT_PREFER_XSL);
			byte[] pdfBytes = pdfStream.toByteArray();
			if (pdfBytes.length == 0) {
				throw new IOException("DOCX 排版转换产出为空");
			}
			return pdfBytes;
		}
		catch (IOException exception) {
			throw exception;
		}
		catch (Exception exception) {
			// docx4j 的 Docx4JException 与 FOP 的各类运行时异常统一收敛为不可读。
			throw new IOException("DOCX 排版转换失败", exception);
		}
	}

	/** 供测试探测字体环境是否可用（缺 CJK 字体的构建机跳过相关用例而不是误报失败）。 */
	public static boolean cjkFontEnvironmentAvailable() {
		try {
			resolveCjkTargetFont();
			return true;
		}
		catch (HealthReportException exception) {
			return false;
		}
	}

	/** 供测试断言实际解析到的替换字体（内置字体生效时应为 {@link #BUNDLED_FONT_FAMILY}）。 */
	static String resolvedCjkFontName() {
		return resolveCjkTargetFont().getName();
	}

	private static PhysicalFont resolveCjkTargetFont() {
		PhysicalFont resolved = cjkTargetFont;
		if (resolved != null) {
			return resolved;
		}
		synchronized (FONT_ENVIRONMENT_LOCK) {
			if (cjkTargetFont != null) {
				return cjkTargetFont;
			}
			if (!fontEnvironmentInitialized) {
				try {
					// 必须先收窄扫描范围，再显式触发物理字体发现（不能依赖 IdentityPlusMapper 静态块的时序）。
					PhysicalFonts.setRegex(PHYSICAL_FONT_SCAN_REGEX);
					PhysicalFonts.discoverPhysicalFonts();
					registerBundledFont();
				}
				catch (Exception exception) {
					throw new HealthReportException(FailCode.SERVER_ERROR, 500, exception);
				}
				fontEnvironmentInitialized = true;
			}
			PhysicalFont bundled = PhysicalFonts.get(BUNDLED_FONT_FAMILY);
			if (bundled != null) {
				cjkTargetFont = bundled;
				log.info("DOCX 转换 CJK 替换字体解析完成，使用内置字体={}", bundled.getName());
				return bundled;
			}
			for (String candidateName : CJK_TARGET_CANDIDATES) {
				PhysicalFont candidate = PhysicalFonts.get(candidateName);
				if (candidate != null) {
					cjkTargetFont = candidate;
					log.warn("DOCX 转换内置字体不可用，退回系统字体={}——跨机器分页一致性依赖内置字体，请排查资源加载失败原因",
							candidate.getName());
					return candidate;
				}
			}
			throw new HealthReportException(FailCode.SERVER_ERROR, 500,
					new IllegalStateException("内置字体加载失败且本机无任何可用 CJK 字体，DOCX 转换无法保真"));
		}
	}

	/** 把内置字体解压到临时文件并注册；失败不抛出（留给系统字体兜底），但必须留告警。 */
	private static void registerBundledFont() {
		try (InputStream fontStream = DocxToPdfConverter.class.getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
			if (fontStream == null) {
				log.warn("classpath 未找到内置字体资源 {}", BUNDLED_FONT_RESOURCE);
				return;
			}
			// FOP 从 jar 内 URL 读字体不可靠，解压到临时文件后按文件 URI 注册。
			Path fontFile = Files.createTempFile("source-han-sans-cn-", ".otf");
			fontFile.toFile().deleteOnExit();
			Files.copy(fontStream, fontFile, StandardCopyOption.REPLACE_EXISTING);
			PhysicalFonts.addPhysicalFont(fontFile.toUri());
		}
		catch (Exception exception) {
			log.warn("内置字体注册失败，将退回系统字体", exception);
		}
	}

}
