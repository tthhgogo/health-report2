package com.example.healthreport.parse;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图像头部尺寸读取与实际解码入口。
 * <p>尺寸读取不会创建整幅 {@link BufferedImage}；调用方必须先完成像素上限判断，再调用解码校验。</p>
 */
@Component
public class ImageContentInspector {

    /**
     * 只读取图像宽高，不整幅解码。
     */
    public ImageDimensions readDimensions(byte[] contentBytes) throws IOException {
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(
                new ByteArrayInputStream(contentBytes))) {
            if (imageInputStream == null) {
                throw new IOException("无法创建图像输入流");
            }
            ImageReader imageReader = firstReader(imageInputStream);
            try {
                imageReader.setInput(imageInputStream, true, true);
                return new ImageDimensions(imageReader.getWidth(0), imageReader.getHeight(0));
            } finally {
                imageReader.dispose();
            }
        }
    }

    /**
     * 实际解码一幅已通过尺寸上限检查的图，确认编码内容完整。
     */
    public void assertActuallyDecodable(byte[] contentBytes) throws IOException {
        BufferedImage image = decodeSubsampled(contentBytes, 3600);
        image.flush();
    }

    /**
     * 用 ImageReader 源采样解码，确保大图不会先创建整幅位图再缩放。
     * 返回图由调用方负责在 finally 中 {@link BufferedImage#flush()}。
     */
    public BufferedImage decodeSubsampled(byte[] contentBytes, int maxLongEdge) throws IOException {
        if (maxLongEdge <= 0) {
            throw new IllegalArgumentException("解码长边上限必须大于零");
        }
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(
                new ByteArrayInputStream(contentBytes))) {
            if (imageInputStream == null) {
                throw new IOException("无法创建图像输入流");
            }
            ImageReader imageReader = firstReader(imageInputStream);
            try {
                imageReader.setInput(imageInputStream, true, true);
                int width = imageReader.getWidth(0);
                int height = imageReader.getHeight(0);
                int sourceSubsampling = Math.max(1,
                        (Math.max(width, height) + maxLongEdge - 1) / maxLongEdge);
                ImageReadParam readParam = imageReader.getDefaultReadParam();
                readParam.setSourceSubsampling(sourceSubsampling, sourceSubsampling, 0, 0);
                BufferedImage image = imageReader.read(0, readParam);
                if (image == null) {
                    throw new IOException("图像解码结果为空");
                }
                return image;
            } finally {
                imageReader.dispose();
            }
        }
    }

    private ImageReader firstReader(ImageInputStream imageInputStream) throws IOException {
        Iterator<ImageReader> imageReaderIterator = ImageIO.getImageReaders(imageInputStream);
        if (!imageReaderIterator.hasNext()) {
            throw new IOException("不支持的图像编码");
        }
        return imageReaderIterator.next();
    }
}
