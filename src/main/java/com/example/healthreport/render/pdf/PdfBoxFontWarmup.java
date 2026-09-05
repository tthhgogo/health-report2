package com.example.healthreport.render.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * PDFBox 系统字体映射异步预热器。
 *
 * <p>应用就绪后在独立守护线程中触发 PDFBox 的系统字体发现与磁盘缓存加载，避免首次
 * PDF 渲染承担完整初始化耗时。预热不属于业务任务，不占用分析线程池；失败仅记录告警，
 * 真实渲染仍可按 PDFBox 原有逻辑再次尝试。</p>
 */
@Slf4j
@Component
public class PdfBoxFontWarmup {

    /** PDF 标准十四字体之一，用于触发默认字体提供器初始化，不依赖业务报告内容。 */
    private static final String WARMUP_FONT_NAME = "Helvetica";

    /** 独立守护线程名称，便于在启动日志和线程快照中识别字体预热。 */
    private static final String WARMUP_THREAD_NAME = "pdfbox-font-warmup";

    /**
     * Spring 完成启动后异步触发字体预热；线程提交失败也不得反向破坏应用就绪状态。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAsynchronously() {
        try {
            Thread warmupThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    warmUp();
                }
            }, WARMUP_THREAD_NAME);
            warmupThread.setDaemon(true);
            warmupThread.start();
        } catch (RuntimeException exception) {
            log.warn("PDFBox 字体映射异步预热线程启动失败，后续首次 PDF 渲染将自行初始化", exception);
        }
    }

    /**
     * 触发 PDFBox 默认字体提供器加载；异常只影响本次预热，不改变应用启动和任务状态。
     */
    void warmUp() {
        long startMillis = System.currentTimeMillis();
        try {
            FontMappers.instance().getFontBoxFont(WARMUP_FONT_NAME, null);
            log.info("PDFBox 字体映射异步预热完成，耗时={}ms", System.currentTimeMillis() - startMillis);
        } catch (RuntimeException | LinkageError exception) {
            log.warn("PDFBox 字体映射异步预热失败，后续首次 PDF 渲染将自行初始化", exception);
        }
    }
}
