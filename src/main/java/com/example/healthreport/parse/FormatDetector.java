package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;

/**
 * 依据文件内容识别六种受支持格式。
 * <p>ZIP 只作为 DOCX/OFD 的容器；普通 ZIP 即使扩展名被改写也会拒绝。</p>
 */
@Component
public class FormatDetector {

    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG_MAGIC = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] OLE2_MAGIC = new byte[]{
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final byte[] ZIP_MAGIC = new byte[]{'P', 'K', 0x03, 0x04};

    private final ZipBombGuard zipBombGuard;

    public FormatDetector(ZipBombGuard zipBombGuard) {
        this.zipBombGuard = zipBombGuard;
    }

    /**
     * 判定真实格式；调用方不得传扩展名参与判断。
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
        if (startsWith(contentBytes, OLE2_MAGIC) && containsWordDocumentStream(contentBytes)) {
            return ContentType.DOC;
        }
        throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400);
    }

    private ContentType detectZipContainer(byte[] contentBytes) {
        Set<String> entryNameSet = zipBombGuard.inspect(contentBytes);
        boolean docx = containsIgnoreCase(entryNameSet, "word/document.xml");
        boolean ofd = containsIgnoreCase(entryNameSet, "OFD.xml");
        if (docx == ofd) {
            throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400);
        }
        return docx ? ContentType.DOCX : ContentType.OFD;
    }

    private boolean containsWordDocumentStream(byte[] contentBytes) {
        try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(contentBytes))) {
            return fileSystem.getRoot().hasEntry("WordDocument");
        } catch (IOException | RuntimeException exception) {
            return false;
        }
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
