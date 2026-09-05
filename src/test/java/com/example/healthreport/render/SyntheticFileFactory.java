package com.example.healthreport.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /** 结构可被 docx4j 排版的合法 DOCX，POI 生成；供 DOCX 支持链路的正向用例使用。 */
    static byte[] validDocx(String bodyText) throws IOException {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(bodyText);
            document.write(output);
            return output.toByteArray();
        }
    }

    /** 残缺的最小 DOCX 容器：能被识别为 DOCX，但排版转换必须失败（可读性负例）。 */
    static byte[] docx(int paragraphCount, int imageCount) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            addZipEntry(zip, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types/>");
            StringBuilder body = new StringBuilder();
            for (int index = 0; index < paragraphCount; index++) {
                body.append("<w:p><w:r><w:t>synthetic segment ").append(index)
                        .append("</w:t></w:r></w:p>");
            }
            addZipEntry(zip, "word/document.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document><w:body>"
                            + body + "</w:body></w:document>");
            zip.finish();
            return output.toByteArray();
        }
    }

    static byte[] emptyDocx() throws IOException {
        return docx(0, 0);
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

    static byte[] doc() throws IOException {
        InputStream input = SyntheticFileFactory.class.getResourceAsStream(
                "/fixtures/synthetic-readable.doc");
        if (input == null) {
            throw new IOException("缺少合成DOC测试夹具");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    /** 非 Word 的 OLE2 复合文档（根目录只有 Workbook 流，形似 XLS）：必须被识别拒绝。 */
    static byte[] nonWordOle2() throws IOException {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem poifs =
                     new org.apache.poi.poifs.filesystem.POIFSFileSystem();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            poifs.createDocument(new java.io.ByteArrayInputStream(
                    "synthetic workbook".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
                    "Workbook");
            poifs.writeFilesystem(output);
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
