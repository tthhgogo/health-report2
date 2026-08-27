package com.example.healthreport.parse;

/**
 * 按文件内容判定的受支持格式，不依赖文件扩展名或客户端 Content-Type。
 */
public enum ContentType {

    /** PDF 文档。 */
    PDF,

    /** JPEG 图片。 */
    JPG,

    /** PNG 图片。 */
    PNG,

    /** OFD 文档。 */
    OFD,

    /** 旧版 OLE2 Word 文档。 */
    DOC,

    /** OOXML Word 文档。 */
    DOCX
}
