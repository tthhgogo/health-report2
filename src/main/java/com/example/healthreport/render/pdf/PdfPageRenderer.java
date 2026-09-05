package com.example.healthreport.render.pdf;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * PDF 页面渲染档渲染器；PDFBox 在渲染阶段归一化页面 Rotate。
 * <p>常量原属 PdfSegmentParser，segment 链路删除后随职责移入本类。</p>
 */
@Component
public class PdfPageRenderer {

    /** 渲染 DPI；小字号表格与上下标在该档下仍可分辨。 */
    public static final int RENDER_DPI = 300;

    /** 渲染档长边上限像素，防止超大页面产出巨型位图。 */
    public static final int MAX_RENDER_LONG_EDGE = 3600;

    /**
     * 渲染指定零起页码。任何渲染失败统一携带 UNREADABLE，不泄露底层文件内容。
     * 返回位图由调用方压缩后立即释放，不得整份缓存。
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
            throw new BusinessException(FailCode.UNREADABLE, exception);
        }
    }

    private float renderScale(PDPage page) throws IOException {
        PDRectangle cropBox = page.getCropBox();
        if (cropBox == null || cropBox.getWidth() <= 0F || cropBox.getHeight() <= 0F
                || page.getUserUnit() <= 0F) {
            throw new IOException("PDF 页面尺寸无效");
        }
        double dpiScale = (double) RENDER_DPI / 72D * page.getUserUnit();
        int rotation = page.getRotation() % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        boolean swapsDimensions = rotation == 90 || rotation == 270;
        double renderedWidth = (swapsDimensions ? cropBox.getHeight() : cropBox.getWidth()) * dpiScale;
        double renderedHeight = (swapsDimensions ? cropBox.getWidth() : cropBox.getHeight()) * dpiScale;
        double longEdge = Math.max(renderedWidth, renderedHeight);
        double capScale = longEdge > MAX_RENDER_LONG_EDGE
                ? (double) MAX_RENDER_LONG_EDGE / longEdge : 1D;
        return (float) (dpiScale * capScale);
    }
}
