package com.example.healthreport.render;

import com.example.healthreport.render.doc.DocToPdfConverter;
import com.example.healthreport.render.docx.DocxToPdfConverter;
import com.example.healthreport.render.image.UploadedImageAdapter;
import com.example.healthreport.render.ofd.OfdPageRenderer;
import com.example.healthreport.render.pdf.PdfImageStripper;
import com.example.healthreport.render.pdf.PdfPageRenderer;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * 文件转图入口：全部文件统一转成 JPEG 页面图，按 fileIndex 顺序拼成全局图序列。
 *
 * <p>这是链路里唯一的转图编排点，也是 {@code IMAGE_TOO_LARGE} 到
 * {@code IMAGE_TOO_LARGE} 的唯一映射点。逐页渲染、逐页压缩、逐页释放，
 * 渲染档位图绝不整份缓存；压缩档三次调用共用一份（设计方案 §3.3）。</p>
 *
     * <p><b>本类不做任何语义判断，也不抽取文本</b>（设计方案 §0-2 的文件转图层职责）。
     * 业务容量在创建任务时已同步裁决；对象完整性、格式安全和精确页数由上游
     * {@code TaskRenderService} 在把字节交给本类之前复核。</p>
 */
@Slf4j
@Service
public class FileToImageService {

    private final PdfPageRenderer pdfPageRenderer;
    private final PdfImageStripper pdfImageStripper;
    private final OfdPageRenderer ofdPageRenderer;
    private final UploadedImageAdapter uploadedImageAdapter;
    private final ExtractionImageCompressor extractionImageCompressor;
    private final DocxToPdfConverter docxToPdfConverter;
    private final DocToPdfConverter docToPdfConverter;

    public FileToImageService(PdfPageRenderer pdfPageRenderer,
                              PdfImageStripper pdfImageStripper,
                              OfdPageRenderer ofdPageRenderer,
                              UploadedImageAdapter uploadedImageAdapter,
                              ExtractionImageCompressor extractionImageCompressor,
                              DocxToPdfConverter docxToPdfConverter,
                              DocToPdfConverter docToPdfConverter) {
        this.pdfPageRenderer = pdfPageRenderer;
        this.pdfImageStripper = pdfImageStripper;
        this.ofdPageRenderer = ofdPageRenderer;
        this.uploadedImageAdapter = uploadedImageAdapter;
        this.extractionImageCompressor = extractionImageCompressor;
        this.docxToPdfConverter = docxToPdfConverter;
        this.docToPdfConverter = docToPdfConverter;
    }

    /**
     * 把任务的全部文件转成全局图序列。
     *
     * @param fileList 已按 fileIndex 升序排好的文件列表
     */
    public PageImageSequence render(List<RenderableFile> fileList) {
        if (fileList == null || fileList.isEmpty()) {
            throw new BusinessException(FailCode.UNREADABLE);
        }
        long startMillis = System.currentTimeMillis();
        // 压缩兜底失败抛的就是 IMAGE_TOO_LARGE 的 BusinessException，直接向上冒泡，不再二次包装。
        PageImageSequence.Builder builder = new PageImageSequence.Builder();
        for (RenderableFile file : fileList) {
            renderFile(file, builder);
        }
        PageImageSequence sequence = builder.build();
        log.info("文件转图完成，文件数={}，总页数={}，耗时={}ms",
                fileList.size(), sequence.size(), System.currentTimeMillis() - startMillis);
        return sequence;
    }

    /** 逐文件按需读取字节，渲染完成即出作用域；绝不同时持有多份原文件。 */
    private void renderFile(RenderableFile file, PageImageSequence.Builder builder) {
        byte[] contentBytes = file.readContentBytes();
        switch (file.getContentType()) {
            case PDF:
                renderPdf(file.getFileIndex(), contentBytes, builder);
                return;
            case OFD:
                renderOfd(file.getFileIndex(), contentBytes, builder);
                return;
            case DOCX:
                // 排版转 PDF 后完全复用 PDF 渲染路径（逐页渲染、逐页压缩、逐页释放）。
                renderPdf(file.getFileIndex(), docxToPdf(contentBytes), builder);
                return;
            case DOC:
                renderPdf(file.getFileIndex(), docToPdf(contentBytes), builder);
                return;
            case JPG:
            case PNG:
                CompressedPageImage adapted =
                        uploadedImageAdapter.adapt(file.getContentType(), contentBytes);
                builder.addPage(file.getFileIndex(), 1, adapted.getJpegBytes());
                return;
            default:
                // 非 Word 的 OLE2 等在上传口已被拒（§5.4），走到这里说明数据被改过。
                throw new BusinessException(FailCode.UNSUPPORTED_FORMAT);
        }
    }

    /** 排版失败按不可读处理；字体环境缺失由转换器直接抛 SERVER_ERROR，不在此改写。 */
    private byte[] docxToPdf(byte[] contentBytes) {
        try {
            return docxToPdfConverter.toPdf(contentBytes);
        } catch (IOException exception) {
            throw new BusinessException(FailCode.UNREADABLE, exception);
        }
    }

    /** 口径同 {@link #docxToPdf}：排版失败即不可读，环境问题由转换器自抛 SERVER_ERROR。 */
    private byte[] docToPdf(byte[] contentBytes) {
        try {
            return docToPdfConverter.toPdf(contentBytes);
        } catch (IOException exception) {
            throw new BusinessException(FailCode.UNREADABLE, exception);
        }
    }

    private void renderPdf(int fileIndex, byte[] contentBytes, PageImageSequence.Builder builder) {
        final PDDocument document;
        try {
            document = PDDocument.load(contentBytes);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(FailCode.UNREADABLE, exception);
        }
        try {
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                throw new BusinessException(FailCode.UNREADABLE);
            }
            // 影像剔除只改内存文档；扫描版保护与失败兜底见 PdfImageStripper。
            pdfImageStripper.stripImages(document);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage renderedImage = pdfPageRenderer.render(document, pageIndex);
                try {
                    compressInto(builder, fileIndex, pageIndex + 1, renderedImage);
                } finally {
                    renderedImage.flush();
                }
            }
        } finally {
            closeQuietly(document);
        }
    }

    private void renderOfd(final int fileIndex, byte[] contentBytes,
                           final PageImageSequence.Builder builder) {
        ofdPageRenderer.renderEachPage(contentBytes, new OfdPageRenderer.PageConsumer() {
            @Override
            public void accept(int pageInFile, BufferedImage renderedImage) {
                compressInto(builder, fileIndex, pageInFile, renderedImage);
            }
        });
    }

    private void compressInto(PageImageSequence.Builder builder, int fileIndex, int pageInFile,
                              BufferedImage renderedImage) {
        CompressedPageImage compressed = extractionImageCompressor.compressForExtraction(renderedImage);
        builder.addPage(fileIndex, pageInFile, compressed.getJpegBytes());
    }

    /** 关闭失败不改变已经产生的失败码，也不覆盖正在抛出的异常。 */
    private void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException | RuntimeException exception) {
            log.warn("PDF 文档关闭失败", exception);
        }
    }
}
