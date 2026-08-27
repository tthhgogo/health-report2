package com.example.healthreport.parse;

import lombok.Getter;

/**
 * 仅包含图像头部给出的像素宽高，不持有整幅解码结果。
 */
@Getter
public class ImageDimensions {

    private final int width;
    private final int height;

    public ImageDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** 返回以 long 计算的总像素，避免宽高乘法溢出。 */
    public long totalPixels() {
        return (long) width * (long) height;
    }
}
