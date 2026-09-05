package com.example.healthreport.render;

/**
 * 按文件内容判定的受支持格式，不依赖文件扩展名或客户端 Content-Type。
 * <p>旧版 DOC 不支持（设计方案 §3.2.1），在 {@link FormatDetector} 识别即拒，
 * 不进入本枚举，也不落 {@code content_type} 列。DOCX 自 2026-09-05 起支持，
 * 经 docx4j 排版转 PDF 后复用 PDF 渲染路径。</p>
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

    /** DOCX 文档；排版转 PDF 后进入图像链路，页数以转换结果为准。 */
    DOCX
}
