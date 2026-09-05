package com.example.healthreport.render;

/**
 * 按文件内容判定的受支持格式，不依赖文件扩展名或客户端 Content-Type。
 * <p>DOCX 与旧版 DOC 均自 2026-09-05 起支持（设计方案 §3.2.1）：DOCX 经 docx4j、
 * DOC 经 POI HWPF 排版转 PDF 后复用 PDF 渲染路径。非 Word 的 OLE2 复合文档
 * （XLS/PPT 等）在 {@link FormatDetector} 识别即拒，不进入本枚举。</p>
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
    DOCX,

    /** 老版二进制 DOC 文档；POI HWPF 排版转 PDF 后进入图像链路，页数以转换结果为准。 */
    DOC
}
