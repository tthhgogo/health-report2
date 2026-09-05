package com.example.healthreport.render.doc;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 老版二进制 DOC 排版转 PDF 的生产链路回归（设计方案 §3.2.1，2026-09-05 恢复 DOC 支持）。
 * <p>验收线与 DOCX 链路一致：中文经内置思源黑体渲染不得出 #；转换全程只在内存进行、
 * 不落文档内容临时文件；图片在转换源头即被丢弃；白字白底的表头文字必须被修复为可见
 * （WordToFoConverter 丢底纹留字色的真实样本教训）。</p>
 */
class DocToPdfConverterTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	private final DocToPdfConverter converter = new DocToPdfConverter();

	@Test
	void fixtureDocShouldConvertToTextualPdfWithoutAnyImages() throws Exception {
		Assumptions.assumeTrue(DocToPdfConverter.fontEnvironmentAvailable(), "内置字体不可用，跳过");
		byte[] pdfBytes = converter.toPdf(fixtureDocBytes());
		try (PDDocument document = PDDocument.load(pdfBytes)) {
			assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
			assertThat(extractNormalizedText(document)).contains("synthetic document segment");
			for (PDPage page : document.getPages()) {
				assertThat(countImageXObjects(page.getResources())).isZero();
			}
		}
	}

	@Test
	void chineseTextShouldRenderWithBundledFontInsteadOfHashes() throws Exception {
		Assumptions.assumeTrue(DocToPdfConverter.fontEnvironmentAvailable(), "内置字体不可用，跳过");
		// HWPF 无法从零创建文档，往夹具里插入中文段落再序列化，得到含 CJK 的合成 DOC。
		String chineseSentence = "血红蛋白浓度轻度升高，建议复查血常规。";
		byte[] docBytes = fixtureDocWithInsertedText(chineseSentence + "\r");
		byte[] pdfBytes = converter.toPdf(docBytes);
		try (PDDocument document = PDDocument.load(pdfBytes)) {
			String pdfText = extractNormalizedText(document);
			assertThat(pdfText).contains("血红蛋白浓度轻度升高");
			// 字体映射失效的典型症状是中文整段渲染成 #（FOP 静默退回 Base14）。
			assertThat(pdfText).doesNotContain("##");
		}
	}

	/**
	 * 转换全程只在内存进行：唯一允许的磁盘写入是首次初始化解压的内置字体
	 * （文件名前缀 doc-source-han-sans-cn-，与文档内容无关）。断言口径与 DOCX
	 * 链路一致——盯 tmpdir 里图片特征文件名，避免并发进程的无关临时文件误报。
	 */
	@Test
	void conversionShouldStayInMemoryWithoutDocumentTempFiles() throws Exception {
		Assumptions.assumeTrue(DocToPdfConverter.fontEnvironmentAvailable(), "内置字体不可用，跳过");
		java.util.Set<String> imageFilesBefore = listTempImageFiles();
		converter.toPdf(fixtureDocBytes());
		assertThat(listTempImageFiles())
				.as("转换不得往 tmpdir 落任何图片临时文件")
				.isEqualTo(imageFilesBefore);
	}

	@Test
	void whiteTextShouldBeRewrittenToVisibleDarkColor() throws Exception {
		org.w3c.dom.Document dom = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().newDocument();
		org.w3c.dom.Element root = dom.createElement("fo:root");
		dom.appendChild(root);
		org.w3c.dom.Element whiteNamed = dom.createElement("fo:block");
		whiteNamed.setAttribute("color", "white");
		org.w3c.dom.Element whiteHex = dom.createElement("fo:inline");
		whiteHex.setAttribute("color", "#FFFFFF");
		org.w3c.dom.Element darkKept = dom.createElement("fo:inline");
		darkKept.setAttribute("color", "#111111");
		root.appendChild(whiteNamed);
		whiteNamed.appendChild(whiteHex);
		root.appendChild(darkKept);

		DocToPdfConverter.FoSanitizeStats stats = DocToPdfConverter.sanitizeFoTree(root);

		assertThat(stats.whiteTextFixes).isEqualTo(2);
		assertThat(whiteNamed.getAttribute("color"))
				.isEqualTo(DocToPdfConverter.WHITE_TEXT_REPLACEMENT_COLOR);
		assertThat(whiteHex.getAttribute("color"))
				.isEqualTo(DocToPdfConverter.WHITE_TEXT_REPLACEMENT_COLOR);
		assertThat(darkKept.getAttribute("color")).isEqualTo("#111111");
	}

	@Test
	void fontFamiliesShouldAllCollapseToBundledFontAndGraphicsShouldBeRemoved() throws Exception {
		org.w3c.dom.Document dom = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().newDocument();
		org.w3c.dom.Element root = dom.createElementNS("http://www.w3.org/1999/XSL/Format", "fo:root");
		dom.appendChild(root);
		org.w3c.dom.Element block = dom.createElementNS("http://www.w3.org/1999/XSL/Format", "fo:block");
		block.setAttribute("font-family", "宋体");
		root.appendChild(block);
		// 纵深防线：POI 无 PicturesManager 时不应产出图形元素，但一旦出现必须被连根移除。
		block.appendChild(dom.createElementNS("http://www.w3.org/1999/XSL/Format",
				"fo:external-graphic"));
		block.appendChild(dom.createElementNS("http://www.w3.org/1999/XSL/Format",
				"fo:instream-foreign-object"));

		DocToPdfConverter.FoSanitizeStats stats = DocToPdfConverter.sanitizeFoTree(root);

		assertThat(block.getAttribute("font-family")).isEqualTo(DocToPdfConverter.BUNDLED_FONT_FAMILY);
		assertThat(stats.graphicsRemoved).isEqualTo(2);
		assertThat(block.getChildNodes().getLength()).isZero();
	}

	/**
	 * .doc 的列表符号常是 Symbol/Wingdings 的私用区码位（真实样本实证 U+F0B7 圆点）：
	 * 字体统一到思源黑体后私用区必然无字形、FOP 渲染成 #，必须替换为通用圆点。
	 */
	@Test
	void privateUseAreaCharsShouldBecomeBulletsInsteadOfHashes() throws Exception {
		org.w3c.dom.Document dom = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder().newDocument();
		org.w3c.dom.Element root = dom.createElementNS("http://www.w3.org/1999/XSL/Format", "fo:root");
		dom.appendChild(root);
		org.w3c.dom.Element block = dom.createElementNS("http://www.w3.org/1999/XSL/Format", "fo:block");
		root.appendChild(block);
		block.appendChild(dom.createTextNode("\uF0B7 定期洁牙 \uE021 正文保持不变"));

		DocToPdfConverter.FoSanitizeStats stats = DocToPdfConverter.sanitizeFoTree(root);

		assertThat(stats.privateUseCharsReplaced).isEqualTo(2);
		assertThat(block.getTextContent()).isEqualTo("• 定期洁牙 • 正文保持不变");
	}

	/**
	 * WordToFoConverter 只排正文：页眉（医院名、报告标题）与页脚必须由转换器自行并入，
	 * 否则静默丢失报告信息。夹具 synthetic-header.doc 由 docx4j authored DOCX 经
	 * Microsoft Word 另存为 97-2004 格式生成，含 odd header 与带 PAGE 域的 footer。
	 */
	@Test
	void headerAndFooterTextShouldSurviveConversion() throws Exception {
		Assumptions.assumeTrue(DocToPdfConverter.fontEnvironmentAvailable(), "内置字体不可用，跳过");
		byte[] pdfBytes = converter.toPdf(fixtureBytes("/fixtures/synthetic-header.doc"));
		try (PDDocument document = PDDocument.load(pdfBytes)) {
			String pdfText = extractNormalizedText(document);
			// 断言避开「页」字：FOP 的 ToUnicode 把 页 映射到无 NFKC 分解的部首码位 ⻚。
			assertThat(pdfText).contains("眉标识").contains("Synthetic Header Marker");
			assertThat(pdfText).contains("脚标识");
			// PAGE 域应转 fo:page-number 取真实页码（缓存旧页码在多页文档里是错的）。
			assertThat(pdfText).contains("第 1");
			assertThat(pdfText).contains("合成正文第一段");
		}
	}

	/**
	 * 「首页不同」的封面页眉与正文页眉不能互相覆盖（评审缺陷：取第一个非空版本应用到
	 * 全页会丢首页专有文字）。夹具 synthetic-header-variants.doc 开启 titlePg，
	 * FIRST 与 DEFAULT 页眉文字不同，footer 只设 DEFAULT；生成方式同 synthetic-header.doc。
	 * <p>首页 footer 未单独设置时回退到普通 footer（POI HeaderStories 的按非空文本选择
	 * 语义，「启用但留空」与「未启用」在 .doc 文本层不可区分），故不对第 1 页的 footer
	 * 有无作断言。</p>
	 */
	@Test
	void firstPageHeaderMustNotBeOverwrittenByDefaultHeader() throws Exception {
		Assumptions.assumeTrue(DocToPdfConverter.fontEnvironmentAvailable(), "内置字体不可用，跳过");
		byte[] pdfBytes = converter.toPdf(fixtureBytes("/fixtures/synthetic-header-variants.doc"));
		try (PDDocument document = PDDocument.load(pdfBytes)) {
			assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
			String firstPageText = extractPageText(document, 1);
			String secondPageText = extractPageText(document, 2);
			// 标记词避开「页」：FOP ToUnicode 把 页 映射到无 NFKC 分解的部首码位 ⻚。
			assertThat(firstPageText).contains("专属").doesNotContain("普通");
			assertThat(secondPageText).contains("普通").doesNotContain("专属");
			assertThat(secondPageText).contains("通用脚部标识");
		}
	}

	/**
	 * POI 的 AbstractWordConverter 会把不支持的域代码与域内文字打进 WARN 日志——
	 * 那是报告内容。转换器类加载时必须把 org.apache.poi.hwpf.converter 整包关断，
	 * 且整个转换过程的日志里不得出现文档正文。
	 */
	@Test
	void poiConverterLogsMustNotLeakReportContent() throws Exception {
		Assumptions.assumeTrue(DocToPdfConverter.fontEnvironmentAvailable(), "内置字体不可用，跳过");
		assertThat(org.slf4j.LoggerFactory
				.getLogger("org.apache.poi.hwpf.converter.AbstractWordConverter").isWarnEnabled())
				.as("POI 转换器包日志必须被关断")
				.isFalse();

		String marker = "日志外泄哨兵文本甲乙丙丁";
		ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger)
				org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture =
				new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
		capture.start();
		rootLogger.addAppender(capture);
		try {
			converter.toPdf(fixtureDocWithInsertedText(marker + "\r"));
		}
		finally {
			rootLogger.detachAppender(capture);
		}
		for (ch.qos.logback.classic.spi.ILoggingEvent event : capture.list) {
			assertThat(event.getFormattedMessage())
					.as("普通日志不得包含文档内容（logger=" + event.getLoggerName() + "）")
					.doesNotContain(marker);
		}
	}

	/**
	 * 字体损坏是构建/部署问题：必须在排版环境初始化时炸出（fontbox 实际解析 + FOP 预热），
	 * 经 resolveFopFactory 归为 SERVER_ERROR，绝不能等到转换阶段被误报成用户文件不可读——
	 * 更不能让 FOP 非严格模式静默跳过坏字体、把中文渲染成 # 的「成功」流出去。
	 */
	@Test
	void corruptFontShouldFailEnvironmentBuildInsteadOfUserFile() throws Exception {
		Path corruptFont = Files.createTempFile("corrupt-font-", ".otf");
		corruptFont.toFile().deleteOnExit();
		Files.write(corruptFont, "not a real otf font".getBytes(
				java.nio.charset.StandardCharsets.US_ASCII));
		assertThatThrownBy(() -> DocToPdfConverter.buildFopFactory(corruptFont))
				.isInstanceOf(IOException.class);
	}

	@Test
	void pageFieldShouldBecomePageNumberWhileOtherFieldsKeepCachedResult() {
		// PAGE 域 → 页码占位；PAGEREF 等仅同前缀的域保留缓存结果、丢弃域代码。
		// \u0013=域开始 \u0014=代码/结果分隔 \u0015=域结束（.doc 页眉页脚的真实编码）。
		java.util.List<java.util.List<Object>> pageParagraphs =
				DocToPdfConverter.parseStoryParagraphs("第 \u0013 PAGE \u00141\u0015 页\r");
		assertThat(pageParagraphs).hasSize(1);
		assertThat(pageParagraphs.get(0).get(0)).isEqualTo("第 ");
		assertThat(pageParagraphs.get(0).get(1)).isNotInstanceOf(String.class);
		assertThat(pageParagraphs.get(0).get(2)).isEqualTo(" 页");

		java.util.List<java.util.List<Object>> refParagraphs = DocToPdfConverter
				.parseStoryParagraphs("见 \u0013 PAGEREF _Toc1 \u0014第9节\u0015\r");
		assertThat(refParagraphs).hasSize(1);
		assertThat(refParagraphs.get(0)).containsExactly("见 ", "第9节");
	}

	@Test
	void emptyAndCorruptInputShouldFailAsUnreadable() {
		assertThatThrownBy(() -> converter.toPdf(new byte[0])).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> converter.toPdf("not an ole2 doc".getBytes(
				java.nio.charset.StandardCharsets.US_ASCII))).isInstanceOf(IOException.class);
	}

	private static byte[] fixtureDocBytes() throws IOException {
		return fixtureBytes("/fixtures/synthetic-readable.doc");
	}

	private static byte[] fixtureBytes(String resourcePath) throws IOException {
		try (InputStream fixture = DocToPdfConverterTest.class
				.getResourceAsStream(resourcePath)) {
			assertThat(fixture).as("缺少测试夹具 " + resourcePath).isNotNull();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = fixture.read(buffer)) >= 0) {
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private static byte[] fixtureDocWithInsertedText(String text) throws IOException {
		try (HWPFDocument hwpfDocument = new HWPFDocument(
				new ByteArrayInputStream(fixtureDocBytes()))) {
			Range range = hwpfDocument.getRange();
			range.insertBefore(text);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			hwpfDocument.write(output);
			return output.toByteArray();
		}
	}

	/** NFKC 归一化抽取：FOP 的 ToUnicode 会把部分汉字映射到部首区码位（前轮实证）。 */
	private static String extractNormalizedText(PDDocument document) throws IOException {
		return Normalizer.normalize(new PDFTextStripper().getText(document), Normalizer.Form.NFKC);
	}

	/** 单页抽取（1 起始），同样做 NFKC 归一化。 */
	private static String extractPageText(PDDocument document, int pageNumber) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setStartPage(pageNumber);
		stripper.setEndPage(pageNumber);
		return Normalizer.normalize(stripper.getText(document), Normalizer.Form.NFKC);
	}

	private static int countImageXObjects(PDResources resources) throws IOException {
		if (resources == null) {
			return 0;
		}
		int imageCount = 0;
		for (COSName xObjectName : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(xObjectName);
			if (xObject instanceof PDImageXObject) {
				imageCount++;
			}
			else if (xObject instanceof PDFormXObject) {
				imageCount += countImageXObjects(((PDFormXObject) xObject).getResources());
			}
		}
		return imageCount;
	}

	/** tmpdir 里图片落盘的特征文件名（覆盖 docx4j 式 <uuid>image<n>.<ext> 在内的常见图片扩展名）。 */
	private static java.util.Set<String> listTempImageFiles() throws IOException {
		Path tmpDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
		try (java.util.stream.Stream<Path> entries = Files.list(tmpDirectory)) {
			java.util.TreeSet<String> imageFiles = new java.util.TreeSet<String>();
			for (Path entry : entries.toArray(Path[]::new)) {
				String name = entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				if (name.matches(".*\\.(png|jpe?g|gif|bmp|emf|wmf|tiff?)")) {
					imageFiles.add(entry.getFileName().toString());
				}
			}
			return imageFiles;
		}
	}

}
