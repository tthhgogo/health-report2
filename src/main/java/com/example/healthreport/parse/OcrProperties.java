package com.example.healthreport.parse;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OCR 接入的必填容量与 EXIF 行为配置。
 * <p>所有接入答案均无默认值；缺失或自相矛盾时在 Spring 启动阶段失败。</p>
 */
@Getter
@Setter
@Slf4j
@Component
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties implements InitializingBean {

    private Long maxEncodedImageBytes;
    private Long maxRequestBodyBytes;
    private OcrRequestEncoding requestEncoding;
    private Boolean acceptsEncodedBytes;
    private Boolean appliesExifOrientation;
    private Boolean returnsImageDimensions;
    @Setter(AccessLevel.NONE)
    private long effectiveOcrImageBytes;

    @Override
    public void afterPropertiesSet() {
        if (acceptsEncodedBytes == null || appliesExifOrientation == null
                || returnsImageDimensions == null || maxEncodedImageBytes == null
                || maxRequestBodyBytes == null || requestEncoding == null) {
            throw new IllegalStateException("OCR 接入参数必须完整配置");
        }
        if (!acceptsEncodedBytes.booleanValue()) {
            throw new IllegalStateException("OCR 必须支持直接接收编码图像字节");
        }
        if (!appliesExifOrientation.booleanValue() && !returnsImageDimensions.booleanValue()) {
            throw new IllegalStateException("OCR 不应用 EXIF 时必须回传识别图像宽高");
        }
        OcrCapacityCalculator calculator = new OcrCapacityCalculator();
        effectiveOcrImageBytes = calculator.calculate(maxEncodedImageBytes.longValue(),
                maxRequestBodyBytes.longValue(), requestEncoding);
        log.info("OCR 有效单图上限计算完成，编码方式={}，有效上限={}字节",
                requestEncoding, effectiveOcrImageBytes);
    }
}
