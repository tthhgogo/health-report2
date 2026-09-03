package com.example.healthreport.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** EXIF Orientation 1 至 8 的确定性图像转正变换。 */
public class ExifOrientationTransform {

    /** 将源图按 Orientation 转正；5 至 8 的输出宽高互换。 */
    public BufferedImage normalize(BufferedImage source, int orientation) {
        if (source == null) {
            throw new IllegalArgumentException("源图不能为空");
        }
        validateOrientation(orientation);
        int targetWidth = swapsDimensions(orientation) ? source.getHeight() : source.getWidth();
        int targetHeight = swapsDimensions(orientation) ? source.getWidth() : source.getHeight();
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, affine(source.getWidth(), source.getHeight(), orientation), null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** 5 至 8 是转置或九十度旋转，宽高必须互换。 */
    public boolean swapsDimensions(int orientation) {
        validateOrientation(orientation);
        return orientation >= 5;
    }

    private AffineTransform affine(int width, int height, int orientation) {
        switch (orientation) {
            case 1:
                return new AffineTransform(1D, 0D, 0D, 1D, 0D, 0D);
            case 2:
                return new AffineTransform(-1D, 0D, 0D, 1D, width, 0D);
            case 3:
                return new AffineTransform(-1D, 0D, 0D, -1D, width, height);
            case 4:
                return new AffineTransform(1D, 0D, 0D, -1D, 0D, height);
            case 5:
                return new AffineTransform(0D, 1D, 1D, 0D, 0D, 0D);
            case 6:
                return new AffineTransform(0D, 1D, -1D, 0D, height, 0D);
            case 7:
                return new AffineTransform(0D, -1D, -1D, 0D, height, width);
            case 8:
                return new AffineTransform(0D, -1D, 1D, 0D, 0D, width);
            default:
                throw new IllegalArgumentException("EXIF Orientation 必须为 1 至 8");
        }
    }

    private void validateOrientation(int orientation) {
        if (orientation < 1 || orientation > 8) {
            throw new IllegalArgumentException("EXIF Orientation 必须为 1 至 8");
        }
    }
}
