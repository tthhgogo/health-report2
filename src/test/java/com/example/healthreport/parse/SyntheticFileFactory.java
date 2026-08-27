package com.example.healthreport.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 仅用于测试的合成文件工厂，不包含真实报告或健康数据。
 */
final class SyntheticFileFactory {

    static {
        // JDK 8 在无图形会话的 macOS 测试进程中必须显式走 headless ImageIO。
        System.setProperty("java.awt.headless", "true");
    }

    private SyntheticFileFactory() {
    }

    static byte[] pdf(int pageCount, String pageText) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (pageText != null && !pageText.isEmpty()) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(PDType1Font.HELVETICA, 10);
                        content.newLineAtOffset(20, 700);
                        content.showText(pageText);
                        content.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLACK.getRGB());
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IOException("测试环境不支持图片编码");
            }
            return output.toByteArray();
        }
    }

    static byte[] docx(int paragraphCount, int imageCount) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < paragraphCount; index++) {
                document.createParagraph().createRun().setText("synthetic segment " + index);
            }
            byte[] imageBytes = image("png", 300, 300);
            for (int index = 0; index < imageCount; index++) {
                XWPFParagraph paragraph = document.createParagraph();
                XWPFRun run = paragraph.createRun();
                run.addPicture(new java.io.ByteArrayInputStream(imageBytes), Document.PICTURE_TYPE_PNG,
                        "synthetic.png", Units.toEMU(300), Units.toEMU(300));
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    static byte[] emptyDocx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.write(output);
            return output.toByteArray();
        }
    }

    static byte[] ofd(int pageCount) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            addZipEntry(zip, "OFD.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<OFD xmlns=\"http://www.ofdspec.org/2016\"><DocBody><DocInfo>"
                            + "<DocID>synthetic-document</DocID></DocInfo>"
                            + "<DocRoot>Doc_0/Document.xml</DocRoot></DocBody></OFD>");
            StringBuilder pages = new StringBuilder();
            for (int index = 0; index < pageCount; index++) {
                pages.append("<Page ID=\"").append(index + 1)
                        .append("\" BaseLoc=\"Pages/Page_").append(index).append("/Content.xml\"/>");
                addZipEntry(zip, "Doc_0/Pages/Page_" + index + "/Content.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<Page xmlns=\"http://www.ofdspec.org/2016\"><Area>"
                                + "<PhysicalBox>0 0 210 297</PhysicalBox></Area><Content/></Page>");
            }
            addZipEntry(zip, "Doc_0/Document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<Document xmlns=\"http://www.ofdspec.org/2016\"><CommonData>"
                            + "<MaxUnitID>100</MaxUnitID><PageArea><PhysicalBox>0 0 210 297</PhysicalBox>"
                            + "</PageArea></CommonData><Pages>" + pages + "</Pages></Document>");
            zip.finish();
            return output.toByteArray();
        }
    }

    static byte[] oldDoc() throws IOException {
        InputStream encodedInput = SyntheticFileFactory.class.getResourceAsStream(
                "/fixtures/synthetic-readable.doc.b64");
        if (encodedInput == null) {
            throw new IOException("缺少合成DOC测试夹具");
        }
        try (InputStream input = Base64.getMimeDecoder().wrap(encodedInput)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static byte[] docxWithOpaqueImage(String paragraphText) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(paragraphText);
            XWPFRun run = document.createParagraph().createRun();
            run.addPicture(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3, 4}),
                    Document.PICTURE_TYPE_WMF, "synthetic.wmf", Units.toEMU(300), Units.toEMU(300));
            document.write(output);
            return output.toByteArray();
        }
    }

    static byte[] ordinaryZip() throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            addZipEntry(zip, "plain.txt", "synthetic");
            zip.finish();
            return output.toByteArray();
        }
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
