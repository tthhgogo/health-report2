package com.example.healthreport.parse.ocr;

import com.example.healthreport.parse.ExifOrientationTransform;
import com.example.healthreport.parse.segment.BBox;

/** 按已确认 OCR EXIF 行为，把 OCR 坐标统一到转正后的左上原点像素坐标系。 */
public class OcrBboxNormalizer {

    private final boolean ocrAppliesExifOrientation;
    private final boolean ocrReturnsImageDimensions;
    private final ExifOrientationTransform exifOrientationTransform;

    public OcrBboxNormalizer(boolean ocrAppliesExifOrientation, boolean ocrReturnsImageDimensions,
                             ExifOrientationTransform exifOrientationTransform) {
        if (!ocrAppliesExifOrientation && !ocrReturnsImageDimensions) {
            throw new IllegalArgumentException("OCR 不应用 EXIF 时必须回传图像宽高");
        }
        this.ocrAppliesExifOrientation = ocrAppliesExifOrientation;
        this.ocrReturnsImageDimensions = ocrReturnsImageDimensions;
        this.exifOrientationTransform = exifOrientationTransform;
    }

    /** OCR 已转正时原样返回；未转正时仅执行固定 Orientation 数学变换，不做第二次 Y 翻转。 */
    public BBox normalize(BBox ocrBox, int orientation, OcrResult ocrResult) {
        if (ocrBox == null) {
            return null;
        }
        if (ocrResult == null) {
            throw new IllegalArgumentException("OCR 结果不能为空");
        }
        if (ocrReturnsImageDimensions
                && (ocrResult.getImageWidth() == null || ocrResult.getImageHeight() == null)) {
            throw new IllegalStateException("OCR 契约声明回传图像宽高但响应缺失");
        }
        if (ocrAppliesExifOrientation) {
            return ocrBox;
        }
        return exifOrientationTransform.transform(ocrBox, ocrResult.getImageWidth().intValue(),
                ocrResult.getImageHeight().intValue(), orientation);
    }
}
