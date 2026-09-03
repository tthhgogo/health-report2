package com.example.healthreport.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** R66g 子进程探针：在受限堆内生成并处理八千万像素的纯色 PNG。 */
public final class LargeImageMemoryProbe {

    /** 固定宽度 10000 像素，与高度相乘得到八千万像素。 */
    private static final int WIDTH = 10000;

    /** 固定高度 8000 像素，与宽度相乘得到八千万像素。 */
    private static final int HEIGHT = 8000;

    private LargeImageMemoryProbe() {
    }

    /** 执行真实源采样解码与 LLM-A 压缩，并把非敏感指标写入指定文件。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("必须提供指标文件路径");
        }
        final AtomicBoolean monitoring = new AtomicBoolean(true);
        final AtomicLong peakUsedBytes = new AtomicLong(usedHeapBytes());
        Thread monitorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (monitoring.get()) {
                    updatePeak(peakUsedBytes, usedHeapBytes());
                    Thread.yield();
                }
            }
        }, "r66g-heap-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
        byte[] pngBytes = createSolidRgbPng();
        ImageContentInspector inspector = new ImageContentInspector();
        ImageDimensions dimensions = inspector.readDimensions(pngBytes);
        if ((long) dimensions.getWidth() * (long) dimensions.getHeight() != 80000000L) {
            throw new IllegalStateException("R66g 样本像素数不正确");
        }
        BufferedImage decoded = inspector.decodeSubsampled(pngBytes, 2000);
        CompressedPageImage compressed;
        try {
            compressed = new ExtractionImageCompressor().compressForExtraction(decoded);
        } finally {
            decoded.flush();
        }
        monitoring.set(false);
        monitorThread.join();
        updatePeak(peakUsedBytes, usedHeapBytes());
        if (Math.max(compressed.getWidth(), compressed.getHeight()) > 2000
                || compressed.sizeBytes() > 1024 * 1024) {
            throw new IllegalStateException("R66g 输出未满足有界压缩约束");
        }
        Path metricPath = Paths.get(args[0]);
        Files.createDirectories(metricPath.getParent());
        String metrics = "sourcePixels=80000000\npeakUsedBytes=" + peakUsedBytes.get()
                + "\ncompressedBytes=" + compressed.sizeBytes() + "\n";
        Files.write(metricPath, metrics.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 用逐行 DEFLATE 生成高压缩比 PNG，生成阶段不创建八千万像素位图。 */
    private static byte[] createSolidRgbPng() throws Exception {
        ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflaterOutput = new DeflaterOutputStream(compressedOutput)) {
            byte[] rowBytes = new byte[1 + WIDTH * 3];
            for (int row = 0; row < HEIGHT; row++) {
                deflaterOutput.write(rowBytes);
            }
        }
        ByteArrayOutputStream pngOutput = new ByteArrayOutputStream();
        pngOutput.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream headerOutput = new ByteArrayOutputStream(13);
        writeInt(headerOutput, WIDTH);
        writeInt(headerOutput, HEIGHT);
        headerOutput.write(new byte[]{8, 2, 0, 0, 0});
        writeChunk(pngOutput, "IHDR", headerOutput.toByteArray());
        writeChunk(pngOutput, "IDAT", compressedOutput.toByteArray());
        writeChunk(pngOutput, "IEND", new byte[0]);
        return pngOutput.toByteArray();
    }

    /** 写入一个带长度和 CRC 的 PNG 数据块。 */
    private static void writeChunk(ByteArrayOutputStream output, String type,
                                   byte[] dataBytes) throws Exception {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(output, dataBytes.length);
        output.write(typeBytes);
        output.write(dataBytes);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(dataBytes);
        writeInt(output, (int) crc.getValue());
    }

    /** 按 PNG 约定写四字节大端整数。 */
    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    /** 返回 JVM 当前已用堆字节数。 */
    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** 用 CAS 更新采样峰值，监控线程与主线程都可安全调用。 */
    private static void updatePeak(AtomicLong peakUsedBytes, long candidateBytes) {
        long previousBytes = peakUsedBytes.get();
        while (candidateBytes > previousBytes
                && !peakUsedBytes.compareAndSet(previousBytes, candidateBytes)) {
            previousBytes = peakUsedBytes.get();
        }
    }
}
