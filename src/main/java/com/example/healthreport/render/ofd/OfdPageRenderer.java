package com.example.healthreport.render.ofd;

import com.example.healthreport.render.pdf.PdfPageRenderer;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.ofdrw.converter.ImageMaker;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.reader.OFDReader;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * OFD 逐页转图（ofdrw-converter ImageMaker）。
 *
 * <p>逐页渲染、逐页交给消费者、逐页释放，任何时刻只有一张渲染档位图在内存——
 * 30 页渲染档同驻会到 GB 级（设计方案 §3.3「逐页渲染逐页释放」）。</p>
 *
 * <p><b>渲染密度按整份文档的最大页物理尺寸封顶</b>：ImageMaker 的每毫米像素数是
 * 全局参数，超大物理尺寸的页面用固定密度会在压缩器介入前分配巨型位图。
 * 先扫一遍页面尺寸，把密度压到「最大页长边 × 密度 ≤ 渲染档长边上限 3600px」。</p>
 */
@Component
public class OfdPageRenderer {

    /** 期望的每毫米像素数：A4 长边 297mm × 12 ≈ 3564px，贴合渲染档长边上限。 */
    static final double PREFERRED_PIXELS_PER_MILLIMETER = 12D;

    /** 单页渲染结果的消费者；实现方处理完立即返回，不得持有位图引用。 */
    public interface PageConsumer {

        /** 处理一页；pageInFile 从 1 起。 */
        void accept(int pageInFile, BufferedImage renderedImage);
    }

    /**
     * 逐页渲染全部页面并交给消费者；任何解析或渲染失败统一映射 UNREADABLE。
     */
    public void renderEachPage(byte[] contentBytes, PageConsumer pageConsumer) {
        try (OFDReader reader = new OFDReader(new ByteArrayInputStream(contentBytes))) {
            int pageCount = reader.getNumberOfPages();
            if (pageCount < 1) {
                throw new HealthReportException(FailCode.UNREADABLE, 400);
            }
            ImageMaker imageMaker = new ImageMaker(reader, cappedPixelsPerMillimeter(reader, pageCount));
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage renderedImage = imageMaker.makePage(pageIndex);
                try {
                    pageConsumer.accept(pageIndex + 1, renderedImage);
                } finally {
                    renderedImage.flush();
                }
            }
        } catch (HealthReportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new HealthReportException(FailCode.UNREADABLE, 400, exception);
        }
    }

    /** 使「最大页长边 × 密度 ≤ 3600px」；尺寸读不出或非法时按 UNREADABLE 失败。 */
    private double cappedPixelsPerMillimeter(OFDReader reader, int pageCount) {
        double maxLongEdgeMillimeters = 0D;
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            ST_Box pageSize = reader.getPageSize(pageIndex);
            if (pageSize == null || pageSize.getWidth() == null || pageSize.getHeight() == null
                    || pageSize.getWidth().doubleValue() <= 0D
                    || pageSize.getHeight().doubleValue() <= 0D) {
                throw new HealthReportException(FailCode.UNREADABLE, 400);
            }
            maxLongEdgeMillimeters = Math.max(maxLongEdgeMillimeters,
                    Math.max(pageSize.getWidth().doubleValue(), pageSize.getHeight().doubleValue()));
        }
        double densityCap = PdfPageRenderer.MAX_RENDER_LONG_EDGE / maxLongEdgeMillimeters;
        return Math.min(PREFERRED_PIXELS_PER_MILLIMETER, densityCap);
    }
}
