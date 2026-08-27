package com.example.healthreport.parse.pdf;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** PDF 页面按 OCR 档一次性渲染；PDFBox 在渲染阶段归一化页面 Rotate。 */
public class PdfPageRenderer {

    /**
     * 渲染指定零起页码。任何渲染失败统一携带 UNREADABLE，不泄露底层文件内容。
     * 返回位图必须交给页面图双消费者入口并立即释放。
     */
    public BufferedImage render(PDDocument document, int pageIndex) {
        try {
            if (document == null || pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                throw new IOException("PDF 渲染页码无效");
            }
            PDPage page = document.getPage(pageIndex);
            float scale = renderScale(page);
            return new PDFRenderer(document).renderImage(pageIndex, scale, ImageType.RGB);
        } catch (IOException | RuntimeException exception) {
            throw new HealthReportException(FailCode.UNREADABLE, 400, exception);
        }
    }

    private float renderScale(PDPage page) throws IOException {
        PDRectangle cropBox = page.getCropBox();
        if (cropBox == null || cropBox.getWidth() <= 0F || cropBox.getHeight() <= 0F
                || page.getUserUnit() <= 0F) {
            throw new IOException("PDF 页面尺寸无效");
        }
        double dpiScale = (double) PdfSegmentParser.RENDER_DPI / 72D * page.getUserUnit();
        int rotation = page.getRotation() % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        boolean swapsDimensions = rotation == 90 || rotation == 270;
        double renderedWidth = (swapsDimensions ? cropBox.getHeight() : cropBox.getWidth()) * dpiScale;
        double renderedHeight = (swapsDimensions ? cropBox.getWidth() : cropBox.getHeight()) * dpiScale;
        double longEdge = Math.max(renderedWidth, renderedHeight);
        double capScale = longEdge > PdfSegmentParser.MAX_RENDER_LONG_EDGE
                ? (double) PdfSegmentParser.MAX_RENDER_LONG_EDGE / longEdge : 1D;
        return (float) (dpiScale * capScale);
    }
}
