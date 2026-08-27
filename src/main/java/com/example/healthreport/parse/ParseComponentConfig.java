package com.example.healthreport.parse;

import com.example.healthreport.infra.PaddleOcrClient;
import com.example.healthreport.parse.ocr.OcrBboxNormalizer;
import com.example.healthreport.parse.ocr.OcrPageSegmentFactory;
import com.example.healthreport.parse.pdf.PdfPageRenderer;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.word.WordCapacityGuard;
import com.example.healthreport.parse.word.WordSegmentParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 解析层里依赖运行期配置的组件装配。
 * <p>这些类刻意不标 {@code @Component}：它们的构造参数来自 {@link OcrProperties}
 * 算出的有效图像上限与接口契约声明，写成注解扫描就只能改成字段注入，
 * 那会让「上限必须大于零」这类构造期断言失效。</p>
 */
@Configuration
public class ParseComponentConfig {

    @Bean
    public ExifOrientationTransform exifOrientationTransform() {
        return new ExifOrientationTransform();
    }

    @Bean
    public PdfPageRenderer pdfPageRenderer() {
        return new PdfPageRenderer();
    }

    @Bean
    public OcrImageEncoder ocrImageEncoder(OcrProperties ocrProperties) {
        return new OcrImageEncoder(ocrProperties.getEffectiveOcrImageBytes());
    }

    /** 一次渲染的两个消费者入口：先 OCR 无损编码，再 LLM-A 压缩，最后释放源位图。 */
    @Bean
    public RenderedPageImageProcessor renderedPageImageProcessor(
            OcrImageEncoder ocrImageEncoder, ExtractionImageCompressor extractionImageCompressor) {
        return new RenderedPageImageProcessor(ocrImageEncoder, extractionImageCompressor);
    }

    @Bean
    public OcrBboxNormalizer ocrBboxNormalizer(OcrProperties ocrProperties,
                                               ExifOrientationTransform exifOrientationTransform) {
        return new OcrBboxNormalizer(
                ocrProperties.getAppliesExifOrientation().booleanValue(),
                ocrProperties.getReturnsImageDimensions().booleanValue(),
                exifOrientationTransform);
    }

    @Bean
    public OcrPageSegmentFactory ocrPageSegmentFactory(TextNormalizer textNormalizer,
                                                       OcrBboxNormalizer ocrBboxNormalizer) {
        return new OcrPageSegmentFactory(textNormalizer, ocrBboxNormalizer);
    }

    @Bean
    public WordSegmentParser wordSegmentParser(TextNormalizer textNormalizer,
                                               ImageContentInspector imageContentInspector,
                                               PaddleOcrClient paddleOcrClient,
                                               WordCapacityGuard wordCapacityGuard,
                                               ZipBombGuard zipBombGuard,
                                               OcrProperties ocrProperties) {
        return new WordSegmentParser(textNormalizer, imageContentInspector, paddleOcrClient,
                wordCapacityGuard, zipBombGuard, ocrProperties.getEffectiveOcrImageBytes());
    }
}
