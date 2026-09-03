package com.example.healthreport.render;

/**
 * 按文件内容判定的受支持格式，不依赖文件扩展名或客户端 Content-Type。
 * <p>DOC/DOCX 第一期不支持（设计方案 §3.2.1），在 {@link FormatDetector} 识别即拒，
 * 不进入本枚举，也不落 {@code content_type} 列。</p>
 */
public enum ContentType {

    /** PDF 文档。 */
    PDF,

    /** JPEG 图片。 */
    JPG,

    /** PNG 图片。 */
    PNG,

    /** OFD 文档。 */
    OFD
}
