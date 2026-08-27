package com.example.healthreport.infra;

import com.example.healthreport.parse.ocr.OcrResult;

/**
 * OCR 调用边界；OCR 只负责识别，不做任何语义判断。
 * <p>入参锁定为编码图像字节，不是条件句：本地整幅解码会把 8000 万像素的图变成
 * 240MB 位图，那正是 §5.6.3 的 ①②③ 想避免的。</p>
 */
public interface PaddleOcrClient {

    /**
     * 识别一张编码图像。
     *
     * @param encodedImageBytes PNG 或 JPEG 的原始编码字节，本地不解码
     * @return 保持服务端输出顺序的识别块
     */
    OcrResult recognize(byte[] encodedImageBytes);
}
