package com.example.healthreport.parse;

import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.support.FailCode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 页面图双消费者、固定压缩档位、实际尺寸 bbox 与源采样解码测试。 */
class ImagePipelineTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void shouldCompressFourThousandPixelImageToPrimaryTierInMemory() throws Exception {
        BufferedImage source = new BufferedImage(3000, 4000, BufferedImage.TYPE_INT_ARGB);
        try {
            CompressedPageImage result = new ExtractionImageCompressor().compressForExtraction(source);

            assertThat(result.getWidth()).isEqualTo(1500);
            assertThat(result.getHeight()).isEqualTo(2000);
            assertThat(result.sizeBytes()).isLessThanOrEqualTo(1024 * 1024);
            assertThat(ImageIO.read(new ByteArrayInputStream(result.getJpegBytes()))).isNotNull();
        } finally {
            source.flush();
        }
    }

    @Test
    void shouldUseActualFallbackDimensionsForBboxAndFailWhenBothTiersExceedLimit() {
        BufferedImage fallbackSource = partiallyNoisyImage(3000, 4000, 2000);
        try {
            CompressedPageImage result = new ExtractionImageCompressor().compressForExtraction(fallbackSource);
            assertThat(Math.max(result.getWidth(), result.getHeight())).isEqualTo(1600);

            BBox scaled = new BBox(300D, 400D, 600D, 800D)
                    .scale(3000, 4000, result.getWidth(), result.getHeight());
            assertThat(scaled.getX()).isEqualTo(120D);
            assertThat(scaled.getY()).isEqualTo(160D);
            assertThat(scaled.getWidth()).isEqualTo(240D);
            assertThat(scaled.getHeight()).isEqualTo(320D);
        } finally {
            fallbackSource.flush();
        }

        BufferedImage noisySource = patternedImage(3000, 4000, 2);
        try {
            assertThatThrownBy(() -> new ExtractionImageCompressor().compressForExtraction(noisySource))
                    .isInstanceOfSatisfying(ImageTooLargeException.class,
                            exception -> assertThat(exception.getFailCode())
                                    .isEqualTo(FailCode.IMAGE_TOO_LARGE));
        } finally {
            noisySource.flush();
        }
    }

    @Test
    void shouldProduceIndependentOcrAndLlmImagesFromOneRender() throws Exception {
        BufferedImage rendered = new BufferedImage(2480, 3508, BufferedImage.TYPE_INT_RGB);
        RenderedPageImageProcessor processor = new RenderedPageImageProcessor(
                new OcrImageEncoder(16L * 1024L * 1024L), new ExtractionImageCompressor());

        PageImageArtifacts artifacts = processor.processAndRelease(rendered);
        BufferedImage ocrImage = ImageIO.read(new ByteArrayInputStream(artifacts.getOcrEncodedImageBytes()));
        BufferedImage extractionImage = ImageIO.read(new ByteArrayInputStream(artifacts.getExtractionImage().getJpegBytes()));

        assertThat(ocrImage.getWidth()).isEqualTo(2480);
        assertThat(ocrImage.getHeight()).isEqualTo(3508);
        assertThat(Math.max(extractionImage.getWidth(), extractionImage.getHeight())).isEqualTo(2000);
        assertThat(artifacts.getOcrEncodedImageBytes())
                .isNotEqualTo(artifacts.getExtractionImage().getJpegBytes());
        ocrImage.flush();
        extractionImage.flush();
    }

    @Test
    void shouldSubsampleDuringDecodeAndRejectInvalidOcrCapacity() throws Exception {
        BufferedImage source = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        byte[] encoded;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(source, "png", output);
            encoded = output.toByteArray();
        } finally {
            source.flush();
        }
        BufferedImage decoded = new ImageContentInspector().decodeSubsampled(encoded, 1000);
        try {
            assertThat(decoded.getWidth()).isLessThanOrEqualTo(1000);
            assertThat(decoded.getHeight()).isLessThanOrEqualTo(1000);
        } finally {
            decoded.flush();
        }

        BufferedImage overCapacity = patternedImage(300, 300, 1);
        try {
            assertThatThrownBy(() -> new OcrImageEncoder(8L).encodeForOcr(overCapacity))
                    .isInstanceOfSatisfying(ImageTooLargeException.class,
                            exception -> assertThat(exception.getFailCode())
                                    .isEqualTo(FailCode.IMAGE_TOO_LARGE));
        } finally {
            overCapacity.flush();
        }
    }

    private BufferedImage patternedImage(int width, int height, int blockSize) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260826L);
        for (int y = 0; y < height; y += blockSize) {
            for (int x = 0; x < width; x += blockSize) {
                int rgb = new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)).getRGB();
                for (int offsetY = 0; offsetY < blockSize && y + offsetY < height; offsetY++) {
                    for (int offsetX = 0; offsetX < blockSize && x + offsetX < width; offsetX++) {
                        image.setRGB(x + offsetX, y + offsetY, rgb);
                    }
                }
            }
        }
        return image;
    }

    private BufferedImage partiallyNoisyImage(int width, int height, int noisyWidth) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260826L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < noisyWidth; x++) {
                image.setRGB(x, y, random.nextInt(1 << 24));
            }
        }
        return image;
    }
}
