package com.example.healthreport.render.pdf;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 影像剔除与扫描版保护（文字版报告页剔影像；无文字页与含整页大图的页原样保留）。
 * <p>合成 PDF 均由 pdfbox 现场生成，不含真实报告内容。</p>
 */
class PdfImageStripperTest {

	private final PdfImageStripper stripper = new PdfImageStripper();

	/** 文字版报告页上的小图（超声/CT 缩略图量级）必须剔除，文字保持可渲染可提取。 */
	@Test
	void textPageWithSmallImagesShouldLoseImagesAndKeepText() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 30);
			drawImage(document, page, 200, 150);

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources())).isZero();
			// 空 Form 原位替换后 Do 算子不悬空，整页照常渲染。
			new PDFRenderer(document).renderImage(0);
			assertThat(new PDFTextStripper().getText(document)).contains("synthetic report line");
		}
	}

	/** 无文字的纯图片页按扫描页保留，一个像素都不动。 */
	@Test
	void pureImagePageShouldStayUntouched() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			drawImage(document, page, 1300, 1800);

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources())).isEqualTo(1);
		}
	}

	/** 双层扫描件（整页底图 + OCR 文本层）：有文字但存在整页量级大图，必须整页保留。 */
	@Test
	void searchableScanPageShouldStayUntouched() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 30);
			// A4 约 8.3×11.7 英寸；1300×1800 像素 ≈ 150DPI 整页扫描，超过 100DPI 判据。
			drawImage(document, page, 1300, 1800);

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources())).isEqualTo(1);
		}
	}

	/** 藏在 Form XObject 里的图片同样要剔（有的生成器把图包进表单对象）。 */
	@Test
	void imageNestedInFormShouldBeStrippedOnTextPage() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 30);
			PDImageXObject image = LosslessFactory.createFromImage(document,
					new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB));
			PDFormXObject form = new PDFormXObject(
					new org.apache.pdfbox.pdmodel.common.PDStream(document));
			form.setBBox(new PDRectangle(120F, 90F));
			PDResources formResources = new PDResources();
			formResources.put(COSName.getPDFName("ImNested"), image);
			form.setResources(formResources);
			page.getResources().put(COSName.getPDFName("FmWrap"), form);

			stripper.stripImages(document);

			// 页面可达的资源树里无图；原始 Form 对象未被原位修改（可能被其他页共享）。
			assertThat(countImagesRecursively(document.getPage(0).getResources())).isZero();
			assertThat(countImageXObjects(form.getResources())).isEqualTo(1);
		}
	}

	/**
	 * 分块扫描件保护：整页扫描拆成三条条带后每条都低于单图面积阈值，且带 OCR 文本层，
	 * 必须靠绘制覆盖率判据整页保留，不得剔成白页。
	 */
	@Test
	void bandedScanPageShouldStayUntouched() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 30);
			// A4 页面 595×842pt；三条 1300×600px 条带铺满整页（每条 78 万像素 < 单图阈值 97 万）。
			for (int bandIndex = 0; bandIndex < 3; bandIndex++) {
				PDImageXObject band = LosslessFactory.createFromImage(document,
						new BufferedImage(1300, 600, BufferedImage.TYPE_INT_RGB));
				try (PDPageContentStream content = new PDPageContentStream(document, page,
						PDPageContentStream.AppendMode.APPEND, true)) {
					content.drawImage(band, 0, bandIndex * 280.7F, 595, 280.7F);
				}
			}

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources())).isEqualTo(3);
		}
	}

	/** 多页共享资源：文字页剔除不得连带清空共享同一资源字典的受保护扫描页。 */
	@Test
	void sharedResourcesShouldNotLeakStrippingIntoProtectedPage() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage textPage = textPage(document, 30);
			PDImageXObject sharedImage = LosslessFactory.createFromImage(document,
					new BufferedImage(200, 150, BufferedImage.TYPE_INT_RGB));
			try (PDPageContentStream content = new PDPageContentStream(document, textPage,
					PDPageContentStream.AppendMode.APPEND, true)) {
				content.drawImage(sharedImage, 40, 400, 150, 110);
			}
			// 无文字的扫描页与文字页共享同一份 PDResources。
			PDPage scanPage = new PDPage(PDRectangle.A4);
			scanPage.setResources(textPage.getResources());
			document.addPage(scanPage);
			try (PDPageContentStream content = new PDPageContentStream(document, scanPage,
					PDPageContentStream.AppendMode.APPEND, true)) {
				content.drawImage(sharedImage, 0, 0, 595, 842);
			}

			stripper.stripImages(document);

			assertThat(countImagesRecursively(document.getPage(0).getResources()))
				.as("文字页的图片应被剔除").isZero();
			assertThat(countImageXObjects(document.getPage(1).getResources()))
				.as("受保护扫描页共享的原始资源必须原样保留").isEqualTo(1);
			// 扫描页照常渲染，不得整页变白。
			new PDFRenderer(document).renderImage(1);
		}
	}

	/**
	 * 内联图片（BI…EI）必须计入覆盖率：两条内联条带 + 一条 XObject 条带铺满扫描页时，
	 * 漏算内联会把覆盖率低估成三分之一，XObject 条带被误删、页面缺一块。
	 */
	@Test
	void inlineImageBandsShouldProtectXObjectStripOnSamePage() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 30);
			PDImageXObject xObjectBand = LosslessFactory.createFromImage(document,
					new BufferedImage(1300, 600, BufferedImage.TYPE_INT_RGB));
			try (PDPageContentStream content = new PDPageContentStream(document, page,
					PDPageContentStream.AppendMode.APPEND, true)) {
				content.drawImage(xObjectBand, 0, 0, 595, 280.7F);
			}
			appendRawContentStream(document, page,
					inlineImageBand(280.7F) + inlineImageBand(561.4F));

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources()))
				.as("内联条带 + XObject 条带铺满整页，XObject 条带不得被剔").isEqualTo(1);
		}
	}

	/** 深层嵌套的 Form 资源链不得引发栈溢出；超出深度预算的页整页保留原样。 */
	@Test
	void deeplyNestedFormResourcesShouldKeepPageUntouched() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 30);
			drawImage(document, page, 200, 150);
			PDFormXObject nested = emptyForm(document);
			for (int depth = 0; depth < 60; depth++) {
				PDFormXObject wrapper = emptyForm(document);
				PDResources wrapperResources = new PDResources();
				wrapperResources.put(COSName.getPDFName("Fm"), nested);
				wrapper.setResources(wrapperResources);
				nested = wrapper;
			}
			page.getResources().put(COSName.getPDFName("FmDeep"), nested);

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources()))
				.as("深度超限的页按原样保留，不剔任何图").isEqualTo(1);
		}
	}

	private PDFormXObject emptyForm(PDDocument document) {
		PDFormXObject form = new PDFormXObject(
				new org.apache.pdfbox.pdmodel.common.PDStream(document));
		form.setBBox(new PDRectangle(1F, 1F));
		return form;
	}

	/** 一条铺满页宽的 2×2 像素 RGB 内联图片带（BI…ID…EI），bottomY 为条带底边。 */
	private String inlineImageBand(float bottomY) {
		return "q\n595 0 0 280.7 0 " + bottomY + " cm\n"
				+ "BI /W 2 /H 2 /CS /RGB /BPC 8 ID       "
				+ "       EI\nQ\n";
	}

	private void appendRawContentStream(PDDocument document, PDPage page, String rawContent)
			throws IOException {
		org.apache.pdfbox.pdmodel.common.PDStream rawStream =
				new org.apache.pdfbox.pdmodel.common.PDStream(document);
		try (java.io.OutputStream output = rawStream.createOutputStream()) {
			output.write(rawContent.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		}
		java.util.List<org.apache.pdfbox.pdmodel.common.PDStream> streamList =
				new java.util.ArrayList<org.apache.pdfbox.pdmodel.common.PDStream>();
		java.util.Iterator<org.apache.pdfbox.pdmodel.common.PDStream> existing =
				page.getContentStreams();
		while (existing.hasNext()) {
			streamList.add(existing.next());
		}
		streamList.add(rawStream);
		page.setContents(streamList);
	}

	/** 少量文字（低于字节阈值）不足以判定为文字版，按扫描页保留——宁可漏删。 */
	@Test
	void pageWithTinyTextShouldStayUntouched() throws Exception {
		try (PDDocument document = new PDDocument()) {
			PDPage page = textPage(document, 1);
			drawImage(document, page, 200, 150);

			stripper.stripImages(document);

			assertThat(countImageXObjects(document.getPage(0).getResources())).isEqualTo(1);
		}
	}

	private PDPage textPage(PDDocument document, int lineCount) throws IOException {
		PDPage page = new PDPage(PDRectangle.A4);
		document.addPage(page);
		try (PDPageContentStream content = new PDPageContentStream(document, page,
				PDPageContentStream.AppendMode.APPEND, true)) {
			content.beginText();
			content.setFont(PDType1Font.HELVETICA, 10);
			content.newLineAtOffset(40, 780);
			for (int index = 0; index < lineCount; index++) {
				content.showText("synthetic report line " + index);
				content.newLineAtOffset(0, -14);
			}
			content.endText();
		}
		return page;
	}

	private void drawImage(PDDocument document, PDPage page, int pixelWidth, int pixelHeight)
			throws IOException {
		PDImageXObject image = LosslessFactory.createFromImage(document,
				new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB));
		try (PDPageContentStream content = new PDPageContentStream(document, page,
				PDPageContentStream.AppendMode.APPEND, true)) {
			content.drawImage(image, 40, 400, 150, 110);
		}
	}

	private int countImageXObjects(PDResources resources) {
		int imageCount = 0;
		for (COSName name : resources.getXObjectNames()) {
			if (resources.isImageXObject(name)) {
				imageCount++;
			}
		}
		return imageCount;
	}

	/** 从给定资源出发递归数图片（含嵌套 Form 内部）。 */
	private int countImagesRecursively(PDResources resources) throws IOException {
		int imageCount = 0;
		for (COSName name : resources.getXObjectNames()) {
			if (resources.isImageXObject(name)) {
				imageCount++;
			} else {
				Object xObject = resources.getXObject(name);
				if (xObject instanceof PDFormXObject
						&& ((PDFormXObject) xObject).getResources() != null) {
					imageCount += countImagesRecursively(((PDFormXObject) xObject).getResources());
				}
			}
		}
		return imageCount;
	}

}
