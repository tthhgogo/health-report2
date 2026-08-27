package com.example.healthreport.parse;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/** 包内共享的确定性图像缩放与编码实现。 */
final class ImageEncodingSupport {

    private ImageEncodingSupport() {
    }

    static BufferedImage resizeRgb(BufferedImage source, int maxLongEdge) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0 || maxLongEdge <= 0) {
            throw new IllegalArgumentException("源图与目标尺寸必须有效");
        }
        int sourceLongEdge = Math.max(source.getWidth(), source.getHeight());
        double scale = sourceLongEdge > maxLongEdge
                ? (double) maxLongEdge / (double) sourceLongEdge : 1D;
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    static byte[] encodeJpeg(BufferedImage rgbImage, float quality) {
        Iterator<ImageWriter> writerIterator = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writerIterator.hasNext()) {
            throw new IllegalStateException("运行环境缺少 JPEG 编码器");
        }
        ImageWriter writer = writerIterator.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageOutputStream imageOutput = null;
        try {
            imageOutput = ImageIO.createImageOutputStream(output);
            writer.setOutput(imageOutput);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgbImage, null, null), writeParam);
            imageOutput.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("图像编码失败", exception);
        } finally {
            writer.dispose();
            if (imageOutput != null) {
                try {
                    imageOutput.close();
                } catch (IOException ignoredException) {
                    // 内存流关闭失败不掩盖主异常，也不记录任何图像内容。
                }
            }
        }
    }

    static byte[] encodePng(BufferedImage source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(source, "png", output)) {
                throw new IllegalStateException("运行环境缺少 PNG 编码器");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("图像编码失败", exception);
        }
    }
}
