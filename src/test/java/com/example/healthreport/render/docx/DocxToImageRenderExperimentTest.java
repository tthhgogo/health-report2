package com.example.healthreport.render.docx;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实样本走生产转换链路（{@link DocxToPdfConverter}，含内置思源黑体）的可视化验证。
 * <p>运行方式：{@code mvn test -Dtest=... -Ddocx.sample=/路径/样本.docx}，未提供样本则跳过；
 * 产物写入 target/docx-render/（可能含个人健康数据，随 mvn clean 清除，不入库）。
 * 与单元回归的区别：本用例的价值是产出可肉眼核对的整册页面图（字体替换、表格对齐、
 * 医学影像），供新增真实样本时做保真抽查。</p>
 */
class DocxToImageRenderExperimentTest {

	@Test
	void realSampleShouldRenderToPageImagesViaProductionConverter() throws Exception {
		String samplePath = System.getProperty("docx.sample");
		Assumptions.assumeTrue(samplePath != null && Files.isRegularFile(Paths.get(samplePath)),
				"未提供 -Ddocx.sample 真实样本，跳过");
		Path outputDirectory = Paths.get("target/docx-render");
		Files.createDirectories(outputDirectory);

		byte[] docxBytes = Files.readAllBytes(Paths.get(samplePath));
		DocxToPdfConverter converter = new DocxToPdfConverter();

		long pdfStartMillis = System.currentTimeMillis();
		byte[] pdfBytes = converter.toPdf(docxBytes);
		long pdfMillis = System.currentTimeMillis() - pdfStartMillis;
		Files.write(outputDirectory.resolve("sample.pdf"), pdfBytes);

		int pageCount;
		long renderStartMillis = System.currentTimeMillis();
		try (PDDocument pdfDocument = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
			pageCount = pdfDocument.getNumberOfPages();
			PDFRenderer renderer = new PDFRenderer(pdfDocument);
			for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
				BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, 150);
				ImageIO.write(pageImage, "png",
						outputDirectory.resolve("page-" + (pageIndex + 1) + ".png").toFile());
			}
		}
		long renderMillis = System.currentTimeMillis() - renderStartMillis;

		System.out.println("===== DOCX 生产链路转图统计 =====");
		System.out.println("替换字体=" + DocxToPdfConverter.resolvedCjkFontName() + "，docx4j→PDF 耗时="
				+ pdfMillis + "ms（PDF " + pdfBytes.length + " 字节），pdfbox 转图耗时=" + renderMillis
				+ "ms，页数=" + pageCount);
		System.out.println("产物：target/docx-render/sample.pdf 与 page-1.." + pageCount + ".png（150 DPI）");

		assertThat(pageCount).isGreaterThanOrEqualTo(1);
		// 生产口径：内置字体必须生效，不允许静默退回系统字体。
		assertThat(DocxToPdfConverter.resolvedCjkFontName())
			.isEqualTo(DocxToPdfConverter.BUNDLED_FONT_FAMILY);
	}

}
