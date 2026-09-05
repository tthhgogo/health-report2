package com.example.healthreport.render;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 依据文件内容识别受支持格式。
 *
 * <p>DOCX 与 OFD 同为 ZIP 容器（magic number 都是 {@code PK\x03\x04}），按标志性条目
 * （{@code word/document.xml} / {@code OFD.xml}）区分；两种都像或都不像的 ZIP 一律拒绝。
 * 旧版 DOC（OLE2 复合文档）仍不支持，识别即拒（设计方案 §3.2.1，DOCX 于 2026-09-05 恢复）。
 * 识别只读 ZIP 条目名，不做全量内容解压。</p>
 */
@Component
public class FormatDetector {

    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] ZIP_MAGIC = new byte[]{'P', 'K', 0x03, 0x04};

    private final ZipBombGuard zipBombGuard;

    public FormatDetector(ZipBombGuard zipBombGuard) {
        this.zipBombGuard = zipBombGuard;
    }

    /**
     * 判定真实格式；调用方不得传扩展名参与判断。
     * DOC / 普通 ZIP / 未知格式一律 UNSUPPORTED_FORMAT。
     */
    public ContentType detect(byte[] contentBytes) {
        if (startsWith(contentBytes, PDF_MAGIC)) {
            return ContentType.PDF;
        }
        if (isJpeg(contentBytes)) {
            return ContentType.JPG;
        }
        if (startsWith(contentBytes, PNG_MAGIC)) {
            return ContentType.PNG;
        }
        if (startsWith(contentBytes, ZIP_MAGIC)) {
            return detectZipContainer(contentBytes);
        }
        // OLE2 复合文档只可能是旧版 DOC：识别即拒，不再查 WordDocument 流（POI 已随 Word 移除）。
        throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400);
    }

    private ContentType detectZipContainer(byte[] contentBytes) {
        Set<String> entryNameSet = zipBombGuard.inspect(contentBytes);
        boolean docx = containsIgnoreCase(entryNameSet, "word/document.xml");
        boolean ofd = containsIgnoreCase(entryNameSet, "OFD.xml");
        if (ofd && !docx) {
            return ContentType.OFD;
        }
        if (docx && !ofd) {
            return ContentType.DOCX;
        }
        // 两种都像、两种都不像的 ZIP：统一按不支持的格式拒绝——
        // 用户拿到「暂不支持该文件格式」而不是「文件无法读取」。
        throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400);
    }

    private boolean containsIgnoreCase(Set<String> entryNameSet, String expectedName) {
        for (String entryName : entryNameSet) {
            if (expectedName.equalsIgnoreCase(entryName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isJpeg(byte[] contentBytes) {
        return contentBytes != null && contentBytes.length >= 3
                && (contentBytes[0] & 0xFF) == 0xFF
                && (contentBytes[1] & 0xFF) == 0xD8
                && (contentBytes[2] & 0xFF) == 0xFF;
    }

    private boolean startsWith(byte[] contentBytes, byte[] prefixBytes) {
        if (contentBytes == null || contentBytes.length < prefixBytes.length) {
            return false;
        }
        for (int index = 0; index < prefixBytes.length; index++) {
            if (contentBytes[index] != prefixBytes[index]) {
                return false;
            }
        }
        return true;
    }
}
