package com.example.healthreport.render.docx;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DOCX 文本抽取路线的评估实验（设计方案 §12-16 相关），不是生产契约。
 * <p>
 * 验证两件事：① 文本型 DOCX 里的指标表格，POI 能否保住「行内五列对齐」——这是参考值
 * 与结果能否对上的前提；② 扫描图片贴进 Word 的「假文本报告」，文本抽取得到什么——
 * 这决定文本路线必须配文本含量探测与渲染回落。结论落定后若不立项，本测试连同
 * pom 的 test 作用域 poi-ooxml 一起移除。
 * </p>
 */
class DocxTextExtractionExperimentTest {

	@Test
	void textBasedReportShouldKeepIndicatorRowsAlignedUnderExplicitTableTraversal() throws Exception {
		byte[] docxBytes = buildTextReportDocx();

		String naiveText;
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes));
				XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
			naiveText = extractor.getText();
		}
		String structuredText;
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
			structuredText = structuredExtract(document);
		}

		System.out.println("===== XWPFWordExtractor 朴素全文抽取 =====");
		System.out.println(naiveText);
		System.out.println("===== 显式遍历（段落 + 表格逐行）抽取 =====");
		System.out.println(structuredText);

		// 朴素抽取：单元格以制表符分隔、一行一条——同一指标的五个字段仍在同一行上。
		assertThat(naiveText).contains("甘油三酯\t2.8\tmmol/L\t0.56~1.70\t偏高");
		assertThat(naiveText).contains("尿酸\t460\tμmol/L\t208~428\t偏高");
		// 显式遍历：能区分「表格行」与「普通段落」，还能看到合并单元格的真实列数。
		assertThat(structuredText).contains("| 项目 | 结果 | 单位 | 参考范围 | 提示 |");
		assertThat(structuredText).contains("| 甘油三酯 | 2.8 | mmol/L | 0.56~1.70 | 偏高 |");
		assertThat(structuredText).contains("| 血脂检查 |");
		assertThat(structuredText).doesNotContain("| 血脂检查 |  |");
		// 总检结论的建议句完整可得——这正是模块三 quote 需要的原文。
		assertThat(structuredText).contains("1. 血脂偏高，建议低脂饮食，定期复查。");
		assertThat(structuredText).contains("2. 尿酸偏高，建议低嘌呤饮食。");
	}

	@Test
	void scannedImagePastedIntoWordShouldYieldNoTextButDetectableEmbeddedPicture() throws Exception {
		byte[] docxBytes = buildImageOnlyDocx();

		String naiveText;
		int embeddedPictureCount;
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
			embeddedPictureCount = document.getAllPictures().size();
			try (XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
				naiveText = extractor.getText();
			}
		}

		System.out.println("===== 扫描件贴图 Word 的文本抽取结果 =====");
		System.out.println("[" + naiveText.trim() + "]（长度=" + naiveText.trim().length() + "，内嵌图片数="
				+ embeddedPictureCount + "）");

		// 「Word 版体检报告」大量是扫描图贴进文档：文本抽取一无所获，但图片可探测——
		// 文本路线必须以此做含量探测，低于阈值时拒绝或回落渲染路线。
		assertThat(naiveText.trim()).isEmpty();
		assertThat(embeddedPictureCount).isEqualTo(1);
	}

	/**
	 * 真实样本评估：{@code mvn test -Dtest=... -Ddocx.sample=/路径/样本.docx} 时运行，
	 * 未提供样本则跳过。完整抽取结果写入 target/（含个人健康数据，随 mvn clean 清除，不入库）。
	 */
	@Test
	void realWorldSampleShouldSurviveStructuredExtraction() throws Exception {
		String samplePath = System.getProperty("docx.sample");
		Assumptions.assumeTrue(samplePath != null && Files.isRegularFile(Paths.get(samplePath)),
				"未提供 -Ddocx.sample 真实样本，跳过");
		byte[] docxBytes = Files.readAllBytes(Paths.get(samplePath));

		int topLevelTableCount = 0;
		int topLevelParagraphCount = 0;
		int rowCount = 0;
		int pictureCount;
		String structuredText;
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
			pictureCount = document.getAllPictures().size();
			for (IBodyElement element : document.getBodyElements()) {
				if (element instanceof XWPFTable) {
					topLevelTableCount++;
					rowCount += ((XWPFTable) element).getRows().size();
				}
				else if (element instanceof XWPFParagraph) {
					topLevelParagraphCount++;
				}
			}
			structuredText = structuredExtract(document);
		}
		String naiveText;
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes));
				XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
			naiveText = extractor.getText();
		}

		Files.write(Paths.get("target/docx-sample-structured.txt"),
				structuredText.getBytes(StandardCharsets.UTF_8));
		Files.write(Paths.get("target/docx-sample-naive.txt"), naiveText.getBytes(StandardCharsets.UTF_8));
		System.out.println("===== 真实样本统计 =====");
		System.out.println("顶层段落=" + topLevelParagraphCount + "，顶层表格=" + topLevelTableCount
				+ "（行数=" + rowCount + "），内嵌图片=" + pictureCount
				+ "，朴素抽取字符数=" + naiveText.length() + "，结构化抽取字符数=" + structuredText.length());
		System.out.println("完整结果见 target/docx-sample-structured.txt / docx-sample-naive.txt");

		assertThat(structuredText.trim()).isNotEmpty();
	}

	private byte[] buildTextReportDocx() throws Exception {
		try (XWPFDocument document = new XWPFDocument()) {
			addParagraph(document, "测试市体检中心 体检报告");
			addParagraph(document, "姓名：测试姓名  性别：男  年龄：42");

			XWPFTable table = document.createTable(5, 5);
			fillRow(table.getRow(0), "项目", "结果", "单位", "参考范围", "提示");
			mergeIntoSectionRow(table.getRow(1), "血脂检查");
			fillRow(table.getRow(2), "甘油三酯", "2.8", "mmol/L", "0.56~1.70", "偏高");
			fillRow(table.getRow(3), "空腹血糖", "5.2", "mmol/L", "3.9~6.1", "正常");
			fillRow(table.getRow(4), "尿酸", "460", "μmol/L", "208~428", "偏高");

			addParagraph(document, "总检结论");
			addParagraph(document, "1. 血脂偏高，建议低脂饮食，定期复查。");
			addParagraph(document, "2. 尿酸偏高，建议低嘌呤饮食。");
			return toBytes(document);
		}
	}

	private byte[] buildImageOnlyDocx() throws Exception {
		try (XWPFDocument document = new XWPFDocument()) {
			BufferedImage scannedPage = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream pngStream = new ByteArrayOutputStream();
			ImageIO.write(scannedPage, "png", pngStream);
			XWPFRun run = document.createParagraph().createRun();
			run.addPicture(new ByteArrayInputStream(pngStream.toByteArray()), XWPFDocument.PICTURE_TYPE_PNG,
					"scan.png", Units.toEMU(80), Units.toEMU(40));
			return toBytes(document);
		}
	}

	private void addParagraph(XWPFDocument document, String text) {
		document.createParagraph().createRun().setText(text);
	}

	private void fillRow(XWPFTableRow row, String... cellTextArray) {
		for (int index = 0; index < cellTextArray.length; index++) {
			row.getCell(index).setText(cellTextArray[index]);
		}
	}

	/** 体检报告常见的「章节名横跨整行」合并单元格：gridSpan=5 并物理移除其余四格。 */
	private void mergeIntoSectionRow(XWPFTableRow row, String sectionName) {
		row.getCell(0).setText(sectionName);
		row.getCell(0).getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(5));
		for (int index = 4; index >= 1; index--) {
			row.removeCell(index);
		}
	}

	private String structuredExtract(XWPFDocument document) {
		StringBuilder builder = new StringBuilder();
		for (IBodyElement element : document.getBodyElements()) {
			if (element instanceof XWPFParagraph) {
				String text = ((XWPFParagraph) element).getText().trim();
				if (text.length() > 0) {
					builder.append(text).append('\n');
				}
			}
			else if (element instanceof XWPFTable) {
				for (XWPFTableRow row : ((XWPFTable) element).getRows()) {
					StringBuilder line = new StringBuilder("|");
					for (XWPFTableCell cell : row.getTableCells()) {
						line.append(' ').append(cell.getText().trim()).append(" |");
					}
					builder.append(line).append('\n');
				}
			}
		}
		return builder.toString();
	}

	private byte[] toBytes(XWPFDocument document) throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		document.write(outputStream);
		return outputStream.toByteArray();
	}

}
