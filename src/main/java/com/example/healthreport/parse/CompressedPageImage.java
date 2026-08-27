package com.example.healthreport.parse;

import lombok.Getter;

import java.util.Arrays;

/** LLM-A 页面 JPEG 及其实际输出尺寸。 */
@Getter
public final class CompressedPageImage {

    private final byte[] jpegBytes;
    private final int width;
    private final int height;

    public CompressedPageImage(byte[] jpegBytes, int width, int height) {
        if (jpegBytes == null || jpegBytes.length == 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("压缩图像内容与尺寸必须有效");
        }
        this.jpegBytes = Arrays.copyOf(jpegBytes, jpegBytes.length);
        this.width = width;
        this.height = height;
    }

    /** 返回防御性副本，避免不可变结果被调用方改写。 */
    public byte[] getJpegBytes() {
        return Arrays.copyOf(jpegBytes, jpegBytes.length);
    }

    /** 返回编码字节数，不为容量判断额外复制数组。 */
    public int sizeBytes() {
        return jpegBytes.length;
    }
}
