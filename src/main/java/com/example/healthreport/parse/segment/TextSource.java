package com.example.healthreport.parse.segment;

/** 文本块的确定性来源，决定后续来源引用校验档位。 */
public enum TextSource {

    /** PDF、OFD 或 Word 原生文本结构。 */
    NATIVE,

    /** 图像识别服务返回的原子文字块。 */
    OCR
}
