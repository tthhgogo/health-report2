package com.example.healthreport.parse.segment;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 原始渲染图上的不可变像素包围盒。
 * <p>坐标原点固定为左上角，X 向右、Y 向下；本类不携带来源坐标系。</p>
 */
@Getter
@EqualsAndHashCode
@ToString
public final class BBox {

    private final double x;
    private final double y;
    private final double width;
    private final double height;

    public BBox(double x, double y, double width, double height) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(width) || !isFinite(height)
                || x < 0D || y < 0D || width < 0D || height < 0D) {
            throw new IllegalArgumentException("bbox 坐标必须是有限非负数");
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** 按实际输出宽高缩放；两个坐标系均为左上原点，因此不翻转 Y。 */
    public BBox scale(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("图像宽高必须大于零");
        }
        double scaleX = (double) targetWidth / (double) sourceWidth;
        double scaleY = (double) targetHeight / (double) sourceHeight;
        return new BBox(x * scaleX, y * scaleY, width * scaleX, height * scaleY);
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
