package com.example.healthreport.parse;

/** OCR 服务请求中的图片编码承载方式。 */
public enum OcrRequestEncoding {

    /** JSON 字段内使用 Base64。 */
    JSON_BASE64,

    /** multipart 表单内使用原始编码字节。 */
    MULTIPART
}
