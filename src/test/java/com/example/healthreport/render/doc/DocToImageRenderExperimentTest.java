package com.example.healthreport.render.doc;

import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.MimeConstants;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.WordToFoConverter;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 老版二进制 DOC 转图路线评估实验：POI HWPF → {@code WordToFoConverter} 产出 XSL-FO，
 * 再用 FOP（docx4j-export-fo 已传递提供）排版为 PDF，末端复用既有 pdfbox 转图。
 * <p>与 DOCX 生产链路（docx4j）平行，仅评估 POI 路线对 .doc 的保真效果；
 * 结论落定前 poi-scratchpad 不进生产依赖。默认吃合成夹具
 * {@code fixtures/synthetic-readable.doc}，可用 {@code -Ddoc.sample=/路径/样本.doc}
 * 换真实样本做肉眼抽查；产物写入 target/doc-render/（可能含个人健康数据，
 * 随 mvn clean 清除，不入库）。</p>
 * <p>字体口径与 DOCX 链路一致：文档声明的字体全部替换为内置思源黑体，
 * 避免 FOP 因缺 CJK 字体把中文渲染成 #。</p>
 */
class DocToImageRenderExperimentTest {

	static {
		System.setProperty("java.awt.headless", "true");
	}

	private static final String BUNDLED_FONT_RESOURCE = "/fonts/SourceHanSansCN-Regular.otf";

	private static final String BUNDLED_FONT_FAMILY = "Source Han Sans CN";

	@Test
	void docShouldRenderToPageImagesViaPoiFoConversion() throws Exception {
		byte[] docBytes = loadSampleBytes();
		Path outputDirectory = Paths.get("target/doc-render");
		Files.createDirectories(outputDirectory);
		// 清掉上一轮的页图，避免换样本后残留旧页误导肉眼核对。
		try (java.util.stream.Stream<Path> leftovers = Files.list(outputDirectory)) {
			for (Path leftover : leftovers.toArray(Path[]::new)) {
				Files.deleteIfExists(leftover);
			}
		}

		// 1) HWPF 解析：顺带抽取纯文本，供与页面图肉眼比对（图上缺的字一眼可见）。
		long foStartMillis = System.currentTimeMillis();
		String extractedText;
		org.w3c.dom.Document foDocument;
		try (HWPFDocument hwpfDocument = new HWPFDocument(new ByteArrayInputStream(docBytes))) {
			try (WordExtractor extractor = new WordExtractor(hwpfDocument)) {
				extractedText = extractor.getText();
			}
			WordToFoConverter converter = new WordToFoConverter(
					DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument());
			converter.processDocument(hwpfDocument);
			foDocument = converter.getDocument();
		}
		Set<String> declaredFontFamilies = replaceAllFontFamilies(foDocument.getDocumentElement());
		long foMillis = System.currentTimeMillis() - foStartMillis;

		// 2) FO → PDF：FOP 注册内置思源黑体；POI 产出的 FO 不保证严格合规，关掉严格校验。
		long pdfStartMillis = System.currentTimeMillis();
		byte[] pdfBytes = renderFoToPdf(foDocument);
		long pdfMillis = System.currentTimeMillis() - pdfStartMillis;
		Files.write(outputDirectory.resolve("sample.pdf"), pdfBytes);

		// 3) PDF → PNG：与生产链路同款 pdfbox 渲染。
		int pageCount;
		long imageStartMillis = System.currentTimeMillis();
		try (PDDocument pdfDocument = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
			pageCount = pdfDocument.getNumberOfPages();
			PDFRenderer renderer = new PDFRenderer(pdfDocument);
			for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
				BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, 150);
				ImageIO.write(pageImage, "png",
						outputDirectory.resolve("page-" + (pageIndex + 1) + ".png").toFile());
			}
		}
		long imageMillis = System.currentTimeMillis() - imageStartMillis;

		System.out.println("===== DOC（POI 路线）转图统计 =====");
		System.out.println("文档声明字体=" + declaredFontFamilies + "，全部替换为=" + BUNDLED_FONT_FAMILY
				+ "，白字改深色处数=" + whiteTextFixCount);
		System.out.println("HWPF→FO 耗时=" + foMillis + "ms，FOP→PDF 耗时=" + pdfMillis + "ms（PDF "
				+ pdfBytes.length + " 字节），pdfbox 转图耗时=" + imageMillis + "ms，页数=" + pageCount);
		System.out.println("抽取文本（前 500 字，供与页面图比对）：");
		System.out.println(extractedText.length() > 500 ? extractedText.substring(0, 500) : extractedText);
		System.out.println("产物：target/doc-render/sample.pdf 与 page-1.." + pageCount + ".png（150 DPI）");

		assertThat(pdfBytes).isNotEmpty();
		assertThat(pageCount).isGreaterThanOrEqualTo(1);
	}

	private byte[] loadSampleBytes() throws IOException {
		String samplePath = System.getProperty("doc.sample");
		if (samplePath != null && Files.isRegularFile(Paths.get(samplePath))) {
			return Files.readAllBytes(Paths.get(samplePath));
		}
		try (InputStream fixture = DocToImageRenderExperimentTest.class
				.getResourceAsStream("/fixtures/synthetic-readable.doc")) {
			assertThat(fixture).as("缺少合成 DOC 测试夹具").isNotNull();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = fixture.read(buffer)) >= 0) {
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	/**
	 * 把 FO 里所有 font-family 统一替换为内置字体，返回文档原本声明的字体族（供日志观察）。
	 * <p>同时把白色文字改成正文深色：WordToFoConverter 丢弃单元格底纹但保留字色，
	 * 深色底纹表头（"小结""初步意见"等）会变成白字白底隐形，真实样本已实证。</p>
	 */
	private Set<String> replaceAllFontFamilies(org.w3c.dom.Element element) {
		Set<String> declared = new TreeSet<>();
		if (element.hasAttribute("font-family")) {
			declared.add(element.getAttribute("font-family"));
			element.setAttribute("font-family", BUNDLED_FONT_FAMILY);
		}
		String color = element.getAttribute("color");
		if ("white".equalsIgnoreCase(color) || "#ffffff".equalsIgnoreCase(color)
				|| "#fff".equalsIgnoreCase(color)) {
			element.setAttribute("color", "#263238");
			whiteTextFixCount++;
		}
		org.w3c.dom.NodeList children = element.getChildNodes();
		for (int index = 0; index < children.getLength(); index++) {
			org.w3c.dom.Node child = children.item(index);
			if (child instanceof org.w3c.dom.Element) {
				declared.addAll(replaceAllFontFamilies((org.w3c.dom.Element) child));
			}
		}
		return declared;
	}

	private int whiteTextFixCount;

	private byte[] renderFoToPdf(org.w3c.dom.Document foDocument) throws Exception {
		Path fontFile = Files.createTempFile("doc-experiment-source-han-", ".otf");
		fontFile.toFile().deleteOnExit();
		try (InputStream fontStream = DocToImageRenderExperimentTest.class
				.getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
			assertThat(fontStream).as("内置字体资源必须存在").isNotNull();
			Files.copy(fontStream, fontFile, StandardCopyOption.REPLACE_EXISTING);
		}
		String fopConf = "<?xml version=\"1.0\"?>\n"
				+ "<fop version=\"1.0\">\n"
				+ "  <renderers>\n"
				+ "    <renderer mime=\"application/pdf\">\n"
				+ "      <fonts>\n"
				+ "        <font embed-url=\"" + fontFile.toUri() + "\">\n"
				+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY + "\" style=\"normal\" weight=\"normal\"/>\n"
				+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY + "\" style=\"normal\" weight=\"bold\"/>\n"
				+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY + "\" style=\"italic\" weight=\"normal\"/>\n"
				+ "          <font-triplet name=\"" + BUNDLED_FONT_FAMILY + "\" style=\"italic\" weight=\"bold\"/>\n"
				+ "        </font>\n"
				+ "      </fonts>\n"
				+ "    </renderer>\n"
				+ "  </renderers>\n"
				+ "</fop>\n";
		FopConfParser confParser = new FopConfParser(
				new ByteArrayInputStream(fopConf.getBytes(StandardCharsets.UTF_8)),
				Paths.get(".").toUri());
		FopFactoryBuilder factoryBuilder = confParser.getFopFactoryBuilder();
		factoryBuilder.setStrictFOValidation(false);
		FopFactory fopFactory = factoryBuilder.build();
		FOUserAgent userAgent = fopFactory.newFOUserAgent();
		try (ByteArrayOutputStream pdfStream = new ByteArrayOutputStream()) {
			Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, userAgent, pdfStream);
			Transformer transformer = TransformerFactory.newInstance().newTransformer();
			transformer.transform(new DOMSource(foDocument), new SAXResult(fop.getDefaultHandler()));
			return pdfStream.toByteArray();
		}
	}

}
