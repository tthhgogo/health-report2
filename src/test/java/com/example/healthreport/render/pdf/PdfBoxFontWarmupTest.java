package com.example.healthreport.render.pdf;

import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** PDFBox 字体预热的异步与失败隔离测试。 */
class PdfBoxFontWarmupTest {

    /** 等待后台预热线程的测试上限；只用于防止失败用例无限挂起。 */
    private static final long ASYNC_WAIT_SECONDS = 3L;

    @Test
    void shouldWarmUpOnNamedDaemonThread() throws InterruptedException {
        FontMapper originalMapper = FontMappers.instance();
        CountDownLatch invocationLatch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<String>();
        AtomicBoolean daemonThread = new AtomicBoolean(false);
        FontMappers.set(new FailingFontMapper(invocationLatch, threadName, daemonThread));

        try {
            PdfBoxFontWarmup warmup = new PdfBoxFontWarmup();

            warmup.warmUpAsynchronously();

            assertThat(invocationLatch.await(ASYNC_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).isEqualTo("pdfbox-font-warmup");
            assertThat(daemonThread.get()).isTrue();
        } finally {
            FontMappers.set(originalMapper);
        }
    }

    @Test
    void warmUpFailureShouldNotEscape() {
        FontMapper originalMapper = FontMappers.instance();
        CountDownLatch invocationLatch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<String>();
        AtomicBoolean daemonThread = new AtomicBoolean(false);
        FontMappers.set(new FailingFontMapper(invocationLatch, threadName, daemonThread));

        try {
            PdfBoxFontWarmup warmup = new PdfBoxFontWarmup();

            assertThatCode(() -> warmup.warmUp()).doesNotThrowAnyException();
        } finally {
            FontMappers.set(originalMapper);
        }
    }

    /** 模拟 PDFBox 字体提供器初始化失败，并记录实际执行线程。 */
    private static class FailingFontMapper implements FontMapper {

        private final CountDownLatch invocationLatch;
        private final AtomicReference<String> threadName;
        private final AtomicBoolean daemonThread;

        FailingFontMapper(CountDownLatch invocationLatch, AtomicReference<String> threadName,
                          AtomicBoolean daemonThread) {
            this.invocationLatch = invocationLatch;
            this.threadName = threadName;
            this.daemonThread = daemonThread;
        }

        @Override
        public FontMapping<TrueTypeFont> getTrueTypeFont(String baseFont,
                                                        PDFontDescriptor fontDescriptor) {
            throw new UnsupportedOperationException("本用例不调用 TrueType 字体映射");
        }

        @Override
        public FontMapping<FontBoxFont> getFontBoxFont(String baseFont,
                                                      PDFontDescriptor fontDescriptor) {
            threadName.set(Thread.currentThread().getName());
            daemonThread.set(Thread.currentThread().isDaemon());
            invocationLatch.countDown();
            throw new IllegalStateException("模拟字体初始化失败");
        }

        @Override
        public CIDFontMapping getCIDFont(String baseFont, PDFontDescriptor fontDescriptor,
                                         PDCIDSystemInfo cidSystemInfo) {
            throw new UnsupportedOperationException("本用例不调用 CID 字体映射");
        }
    }
}
