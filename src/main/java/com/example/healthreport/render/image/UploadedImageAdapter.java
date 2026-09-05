package com.example.healthreport.render.image;

import com.example.healthreport.render.CompressedPageImage;
import com.example.healthreport.render.ContentType;
import com.example.healthreport.render.ExifOrientationTransform;
import com.example.healthreport.render.ExtractionImageCompressor;
import com.example.healthreport.render.ImageContentInspector;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 上传的 JPG/PNG 到页面图的适配器：降采样解码 → EXIF Orientation 归一化 → 压缩档。
 *
 * <p>大图不整幅解码（8000 万像素的整幅位图约 240MB），降采样上限取渲染档长边；
 * 方向归一化在压缩之前完成，三次模型调用收到的都是文字正向的图。</p>
 */
@Component
public class UploadedImageAdapter {

    /** 降采样解码长边上限，与 PDF 渲染档一致。 */
    static final int DECODE_LONG_EDGE = 3600;

    private final ImageContentInspector imageContentInspector;
    private final ExtractionImageCompressor extractionImageCompressor;
    private final ExifOrientationTransform orientationTransform = new ExifOrientationTransform();

    public UploadedImageAdapter(ImageContentInspector imageContentInspector,
                                ExtractionImageCompressor extractionImageCompressor) {
        this.imageContentInspector = imageContentInspector;
        this.extractionImageCompressor = extractionImageCompressor;
    }

    /** 把一张上传图变成一页压缩档页面图。 */
    public CompressedPageImage adapt(ContentType contentType, byte[] contentBytes) {
        int orientation = contentType == ContentType.JPG
                ? ExifOrientationReader.read(contentBytes) : 1;
        final BufferedImage decodedImage;
        try {
            decodedImage = imageContentInspector.decodeSubsampled(contentBytes, DECODE_LONG_EDGE);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(FailCode.UNREADABLE, exception);
        }
        BufferedImage normalizedImage = null;
        try {
            BufferedImage sourceForCompress = decodedImage;
            if (orientation != 1) {
                normalizedImage = orientationTransform.normalize(decodedImage, orientation);
                sourceForCompress = normalizedImage;
            }
            return extractionImageCompressor.compressForExtraction(sourceForCompress);
        } finally {
            decodedImage.flush();
            if (normalizedImage != null) {
                normalizedImage.flush();
            }
        }
    }
}
