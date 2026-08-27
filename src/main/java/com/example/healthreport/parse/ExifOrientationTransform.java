package com.example.healthreport.parse;

import com.example.healthreport.parse.segment.BBox;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/** EXIF Orientation 1 至 8 的确定性图像与坐标变换。 */
public class ExifOrientationTransform {

    /** 按 Orientation 把包围盒变换到转正后的左上原点坐标系。 */
    public BBox transform(BBox sourceBox, int sourceWidth, int sourceHeight, int orientation) {
        validate(sourceBox, sourceWidth, sourceHeight, orientation);
        double[][] cornerArray = new double[][]{
                point(sourceBox.getX(), sourceBox.getY(), sourceWidth, sourceHeight, orientation),
                point(sourceBox.getX() + sourceBox.getWidth(), sourceBox.getY(),
                        sourceWidth, sourceHeight, orientation),
                point(sourceBox.getX(), sourceBox.getY() + sourceBox.getHeight(),
                        sourceWidth, sourceHeight, orientation),
                point(sourceBox.getX() + sourceBox.getWidth(), sourceBox.getY() + sourceBox.getHeight(),
                        sourceWidth, sourceHeight, orientation)
        };
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] corner : cornerArray) {
            minX = Math.min(minX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxX = Math.max(maxX, corner[0]);
            maxY = Math.max(maxY, corner[1]);
        }
        return new BBox(minX, minY, maxX - minX, maxY - minY);
    }

    /** 将源图按 Orientation 转正；5 至 8 的输出宽高互换。 */
    public BufferedImage normalize(BufferedImage source, int orientation) {
        if (source == null) {
            throw new IllegalArgumentException("源图不能为空");
        }
        validate(new BBox(0D, 0D, source.getWidth(), source.getHeight()),
                source.getWidth(), source.getHeight(), orientation);
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

    private double[] point(double x, double y, int width, int height, int orientation) {
        switch (orientation) {
            case 1:
                return new double[]{x, y};
            case 2:
                return new double[]{width - x, y};
            case 3:
                return new double[]{width - x, height - y};
            case 4:
                return new double[]{x, height - y};
            case 5:
                return new double[]{y, x};
            case 6:
                return new double[]{height - y, x};
            case 7:
                return new double[]{height - y, width - x};
            case 8:
                return new double[]{y, width - x};
            default:
                throw new IllegalArgumentException("EXIF Orientation 必须为 1 至 8");
        }
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

    private void validate(BBox sourceBox, int width, int height, int orientation) {
        if (sourceBox == null || width <= 0 || height <= 0
                || sourceBox.getX() + sourceBox.getWidth() > width
                || sourceBox.getY() + sourceBox.getHeight() > height) {
            throw new IllegalArgumentException("源坐标必须位于图像范围内");
        }
        validateOrientation(orientation);
    }

    private void validateOrientation(int orientation) {
        if (orientation < 1 || orientation > 8) {
            throw new IllegalArgumentException("EXIF Orientation 必须为 1 至 8");
        }
    }
}
