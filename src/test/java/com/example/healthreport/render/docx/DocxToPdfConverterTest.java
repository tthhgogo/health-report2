package com.example.healthreport.render.docx;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOCX 排版转 PDF 的单元回归：能转、确定性、坏文件拒绝。
 * <p>正向用例依赖本机存在候选 CJK 字体，缺字体的构建机跳过而不是误报失败——
 * 这与生产口径一致：字体缺失是 SERVER_ERROR 级部署问题，不是文件问题。</p>
 */
class DocxToPdfConverterTest {

	private final DocxToPdfConverter converter = new DocxToPdfConverter();

	@Test
	void chineseDocxShouldConvertToPdfDeterministically() throws Exception {
		Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
		byte[] docxBytes = syntheticDocx("总检结论：血脂偏高，建议低脂饮食，定期复查。");

		byte[] firstPdf = converter.toPdf(docxBytes);
		byte[] secondPdf = converter.toPdf(docxBytes);

		try (PDDocument firstDocument = PDDocument.load(new ByteArrayInputStream(firstPdf));
				PDDocument secondDocument = PDDocument.load(new ByteArrayInputStream(secondPdf))) {
			assertThat(firstDocument.getNumberOfPages()).isGreaterThanOrEqualTo(1);
			// 页数确定性是「precheck_pages 恒为精确页数」契约对 DOCX 成立的前提。
			assertThat(secondDocument.getNumberOfPages()).isEqualTo(firstDocument.getNumberOfPages());
		}
	}

	/** 内置字体必须生效：跨机器分页一致性依赖它，退回系统字体属环境异常。 */
	@Test
	void bundledSourceHanSansShouldBeTheResolvedCjkFont() {
		assertThat(DocxToPdfConverter.cjkFontEnvironmentAvailable()).isTrue();
		assertThat(DocxToPdfConverter.resolvedCjkFontName())
			.isEqualTo(DocxToPdfConverter.BUNDLED_FONT_FAMILY);
	}

	/**
	 * 字体文件 SHA-256 钉死：换字体等于换排版结果（分页可能漂移），必须显式过评审，
	 * 与 TagRuleVersion 的「内容变更必须显形」同一思路。
	 */
	@Test
	void bundledFontBytesShouldMatchThePinnedDigest() throws Exception {
		try (java.io.InputStream fontStream = DocxToPdfConverter.class
			.getResourceAsStream(DocxToPdfConverter.BUNDLED_FONT_RESOURCE)) {
			assertThat(fontStream).as("内置字体资源必须存在").isNotNull();
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int read;
			while ((read = fontStream.read(buffer)) >= 0) {
				digest.update(buffer, 0, read);
			}
			StringBuilder hex = new StringBuilder();
			for (byte b : digest.digest()) {
				hex.append(String.format("%02x", b));
			}
			assertThat(hex.toString())
				.isEqualTo("e2bc8a2e7f37474b774fff8db758681ece40bb6947a90d571bce9dd60671a8e4");
		}
	}

	/**
	 * 图片一律丢弃：内嵌医学影像不得进入产物，也不得经默认文件型处理器落盘残留
	 * （评审缺陷：<uuid>image1.png 留在 java.io.tmpdir，任务删除链路不会清它）。
	 */
	@Test
	void embeddedImageShouldBeDroppedWithoutTempFiles() throws Exception {
		Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
		byte[] docxBytes = docxWithEmbeddedPng("图片前文字", "图片后文字");
		java.util.Set<String> tempFilesBefore = listTempImageFiles();

		byte[] pdfBytes = converter.toPdf(docxBytes);

		assertThat(listTempImageFiles()).isEqualTo(tempFilesBefore);
		try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
			for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
				for (org.apache.pdfbox.cos.COSName xObjectName : page.getResources().getXObjectNames()) {
					assertThat(page.getResources().isImageXObject(xObjectName))
						.as("产物 PDF 不得包含任何图片对象").isFalse();
				}
			}
			assertThat(extractPdfText(document)).contains("图片前文字").contains("图片后文字");
		}
	}

	/**
	 * 页眉/页脚内嵌图片同样不得落盘：docx4j 测算页眉页脚高度时另建转换配置，
	 * 不继承主配置的图片处理器，必须靠元素级移除兜住（评审绕过缺陷）。
	 */
	@Test
	void headerAndFooterEmbeddedImagesShouldBeDroppedWithoutTempFiles() throws Exception {
		Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
		byte[] docxBytes = docxWithHeaderFooterEmbeddedPng();
		java.util.Set<String> tempFilesBefore = listTempImageFiles();

		byte[] pdfBytes = converter.toPdf(docxBytes);

		assertThat(listTempImageFiles()).isEqualTo(tempFilesBefore);
		try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
			for (org.apache.pdfbox.pdmodel.PDPage page : document.getPages()) {
				for (org.apache.pdfbox.cos.COSName xObjectName : page.getResources().getXObjectNames()) {
					assertThat(page.getResources().isImageXObject(xObjectName))
						.as("页眉页脚图片也不得进入产物 PDF").isFalse();
				}
			}
			// 「页」的 ToUnicode 落在 CJK 部首补充区（U+2EDA，无 NFKC 分解），断言避开该字。
			assertThat(extractPdfText(document)).contains("正文文字").contains("眉文字")
				.contains("脚文字");
		}
	}

	/** 页眉（DrawingML）与页脚（VML）的外链图片都不得触发任何 HTTP 读取。 */
	@Test
	void headerAndFooterExternalImagesShouldNeverTriggerHttpFetch() throws Exception {
		Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
		com.github.tomakehurst.wiremock.WireMockServer server =
				new com.github.tomakehurst.wiremock.WireMockServer(
						com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
		server.start();
		try {
			byte[] docxBytes = docxWithHeaderFooterExternalImages(
					"http://127.0.0.1:" + server.port() + "/header-leak.png",
					"http://127.0.0.1:" + server.port() + "/footer-leak.png");

			byte[] pdfBytes = converter.toPdf(docxBytes);

			assertThat(server.getAllServeEvents())
				.as("页眉/页脚外链图片不得触发任何 HTTP 读取").isEmpty();
			try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
				assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
			}
		}
		finally {
			server.stop();
		}
	}

	/** 外链图片零网络请求：转换正常完成、图片被丢弃，本地 HTTP 服务不得收到任何请求（SSRF 红线）。 */
	@Test
	void externalImageShouldNeverTriggerHttpFetch() throws Exception {
		Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
		com.github.tomakehurst.wiremock.WireMockServer server =
				new com.github.tomakehurst.wiremock.WireMockServer(
						com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
		server.start();
		try {
			byte[] docxBytes = docxWithExternalImage(
					"http://127.0.0.1:" + server.port() + "/leak.png");

			byte[] pdfBytes = converter.toPdf(docxBytes);

			assertThat(server.getAllServeEvents()).as("外链图片不得触发任何 HTTP 读取").isEmpty();
			try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
				assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
			}
		}
		finally {
			server.stop();
		}
	}

	/**
	 * 字形保证（开发方案 §字形要求）：候选表之外的声明字体与未声明字体（文档默认）都必须
	 * 落到内置 CJK 字体上，中文不得静默变成 #。只验页数挡不住这类回归。
	 */
	@Test
	void undeclaredAndDefaultFontChineseShouldKeepGlyphs() throws Exception {
		Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
		byte[] docxBytes;
		try (XWPFDocument document = new XWPFDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			org.apache.poi.xwpf.usermodel.XWPFRun undeclaredFontRun =
					document.createParagraph().createRun();
			undeclaredFontRun.setFontFamily("华文仿宋");
			undeclaredFontRun.setText("华文仿宋审查样本文字");
			document.createParagraph().createRun().setText("默认字体审查样本文字");
			document.write(output);
			docxBytes = output.toByteArray();
		}

		byte[] pdfBytes = converter.toPdf(docxBytes);

		try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
			String extractedText = extractPdfText(document);
			assertThat(extractedText).contains("华文仿宋审查样本文字").contains("默认字体审查样本文字");
			assertThat(extractedText).as("中文缺字形会被 FOP 渲染成 #").doesNotContain("#");
		}
	}

	/**
	 * 提取 PDF 文本并做 NFKC 规范化：FOP 依字体 cmap 反查 ToUnicode，同形字可能映射到
	 * 康熙部首码位（如「文」→ U+2F8C），规范化后才能与原文比对；渲染字形本身是正确的。
	 */
	private static String extractPdfText(PDDocument document) throws IOException {
		return java.text.Normalizer.normalize(
				new org.apache.pdfbox.text.PDFTextStripper().getText(document),
				java.text.Normalizer.Form.NFKC);
	}

	@Test
	void corruptBytesShouldFailAsUnreadableNotServerError() {
		assertThatThrownBy(() -> converter.toPdf(new byte[] { 'P', 'K', 3, 4, 0, 0 }))
			.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> converter.toPdf(new byte[0])).isInstanceOf(IOException.class);
	}

	private byte[] syntheticDocx(String bodyText) throws IOException {
		try (XWPFDocument document = new XWPFDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText(bodyText);
			document.write(output);
			return output.toByteArray();
		}
	}

	private byte[] docxWithEmbeddedPng(String textBefore, String textAfter) throws Exception {
		java.awt.image.BufferedImage image =
				new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream pngStream = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "png", pngStream);
		try (XWPFDocument document = new XWPFDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText(textBefore);
			org.apache.poi.xwpf.usermodel.XWPFRun pictureRun =
					document.createParagraph().createRun();
			pictureRun.addPicture(new ByteArrayInputStream(pngStream.toByteArray()),
					XWPFDocument.PICTURE_TYPE_PNG, "synthetic.png",
					org.apache.poi.util.Units.toEMU(20), org.apache.poi.util.Units.toEMU(20));
			document.createParagraph().createRun().setText(textAfter);
			document.write(output);
			return output.toByteArray();
		}
	}

	private byte[] docxWithHeaderFooterEmbeddedPng() throws Exception {
		java.awt.image.BufferedImage image =
				new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream pngStream = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "png", pngStream);
		byte[] pngBytes = pngStream.toByteArray();
		try (XWPFDocument document = new XWPFDocument();
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.createParagraph().createRun().setText("正文文字");
			org.apache.poi.xwpf.usermodel.XWPFHeader header =
					document.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
			org.apache.poi.xwpf.usermodel.XWPFRun headerRun =
					header.createParagraph().createRun();
			headerRun.setText("页眉文字");
			headerRun.addPicture(new ByteArrayInputStream(pngBytes),
					XWPFDocument.PICTURE_TYPE_PNG, "header.png",
					org.apache.poi.util.Units.toEMU(20), org.apache.poi.util.Units.toEMU(20));
			org.apache.poi.xwpf.usermodel.XWPFFooter footer =
					document.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
			org.apache.poi.xwpf.usermodel.XWPFRun footerRun =
					footer.createParagraph().createRun();
			footerRun.setText("页脚文字");
			footerRun.addPicture(new ByteArrayInputStream(pngBytes),
					XWPFDocument.PICTURE_TYPE_PNG, "footer.png",
					org.apache.poi.util.Units.toEMU(20), org.apache.poi.util.Units.toEMU(20));
			document.write(output);
			return output.toByteArray();
		}
	}

	/** 手工拼最小 DOCX：页眉 DrawingML 外链图 + 页脚 VML 外链图，关系均 TargetMode="External"。 */
	private byte[] docxWithHeaderFooterExternalImages(String headerImageUrl, String footerImageUrl)
			throws IOException {
		String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
				+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
				+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
				+ "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
				+ "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>"
				+ "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>"
				+ "</Types>";
		String packageRels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
				+ "</Relationships>";
		String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
				+ " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
				+ "<w:body><w:p><w:r><w:t>header footer probe</w:t></w:r></w:p>"
				+ "<w:sectPr>"
				+ "<w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/>"
				+ "<w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>"
				+ "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
				+ "<w:pgMar w:top=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:right=\"1440\""
				+ " w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>"
				+ "</w:sectPr></w:body></w:document>";
		String documentRels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rIdHeader\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>"
				+ "<Relationship Id=\"rIdFooter\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>"
				+ "</Relationships>";
		String headerXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
				+ " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
				+ " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
				+ " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
				+ " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
				+ "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
				+ "<wp:extent cx=\"914400\" cy=\"914400\"/><wp:docPr id=\"1\" name=\"hdrExt\"/>"
				+ "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
				+ "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"1\" name=\"hdrExt\"/><pic:cNvPicPr/></pic:nvPicPr>"
				+ "<pic:blipFill><a:blip r:link=\"rId100\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
				+ "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
				+ "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
				+ "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p></w:hdr>";
		String headerRels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId100\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
				+ " Target=\"" + headerImageUrl + "\" TargetMode=\"External\"/>"
				+ "</Relationships>";
		String footerXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
				+ " xmlns:v=\"urn:schemas-microsoft-com:vml\""
				+ " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
				+ "<w:p><w:r><w:pict>"
				+ "<v:shape style=\"width:40pt;height:40pt\"><v:imagedata r:id=\"rId200\"/></v:shape>"
				+ "</w:pict></w:r></w:p></w:ftr>";
		String footerRels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId200\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
				+ " Target=\"" + footerImageUrl + "\" TargetMode=\"External\"/>"
				+ "</Relationships>";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
			addZipEntry(zip, "[Content_Types].xml", contentTypes);
			addZipEntry(zip, "_rels/.rels", packageRels);
			addZipEntry(zip, "word/document.xml", documentXml);
			addZipEntry(zip, "word/_rels/document.xml.rels", documentRels);
			addZipEntry(zip, "word/header1.xml", headerXml);
			addZipEntry(zip, "word/_rels/header1.xml.rels", headerRels);
			addZipEntry(zip, "word/footer1.xml", footerXml);
			addZipEntry(zip, "word/_rels/footer1.xml.rels", footerRels);
		}
		return output.toByteArray();
	}

	/** 手工拼最小 DOCX：document.xml 里一张 r:link 外链图片，关系 TargetMode="External"。 */
	private byte[] docxWithExternalImage(String imageUrl) throws IOException {
		String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
				+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
				+ "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
				+ "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
				+ "</Types>";
		String packageRels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
				+ "</Relationships>";
		String documentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
				+ " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
				+ " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
				+ " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
				+ " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
				+ "<w:body><w:p><w:r><w:t>external image probe</w:t></w:r></w:p>"
				+ "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
				+ "<wp:extent cx=\"914400\" cy=\"914400\"/><wp:docPr id=\"1\" name=\"ext\"/>"
				+ "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
				+ "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"1\" name=\"ext\"/><pic:cNvPicPr/></pic:nvPicPr>"
				+ "<pic:blipFill><a:blip r:link=\"rId100\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
				+ "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm>"
				+ "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
				+ "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
				+ "</w:body></w:document>";
		String documentRels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
				+ "<Relationship Id=\"rId100\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
				+ " Target=\"" + imageUrl + "\" TargetMode=\"External\"/>"
				+ "</Relationships>";
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
			addZipEntry(zip, "[Content_Types].xml", contentTypes);
			addZipEntry(zip, "_rels/.rels", packageRels);
			addZipEntry(zip, "word/document.xml", documentXml);
			addZipEntry(zip, "word/_rels/document.xml.rels", documentRels);
		}
		return output.toByteArray();
	}

	private static void addZipEntry(java.util.zip.ZipOutputStream zip, String name, String text)
			throws IOException {
		zip.putNextEntry(new java.util.zip.ZipEntry(name));
		zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	/** java.io.tmpdir 里 docx4j 文件型图片处理器的落盘特征文件名（<uuid>image<n>.<ext>）。 */
	private static java.util.Set<String> listTempImageFiles() {
		java.io.File tempDirectory = new java.io.File(System.getProperty("java.io.tmpdir"));
		String[] names = tempDirectory.list((directory, name) -> name.contains("image"));
		return names == null ? java.util.Collections.emptySet()
				: new java.util.TreeSet<>(java.util.Arrays.asList(names));
	}

}
