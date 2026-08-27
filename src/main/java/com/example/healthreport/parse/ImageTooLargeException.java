package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.Getter;

/** 图像编码无法满足已确认字节上限，固定归属 IMAGE_TOO_LARGE 且不携带图像内容。 */
@Getter
public class ImageTooLargeException extends HealthReportException {

    private static final long serialVersionUID = 1L;

    private final long actualBytes;
    private final long maxBytes;

    public ImageTooLargeException(long actualBytes, long maxBytes) {
        super(FailCode.IMAGE_TOO_LARGE, 400);
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }
}
