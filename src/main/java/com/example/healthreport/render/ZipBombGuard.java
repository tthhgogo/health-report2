package com.example.healthreport.render;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DOCX/OFD ZIP 容器的流式解压炸弹防护。
 * <p>所有体积都以实际读取字节计数，绝不信任 ZIP 条目头声明的解压大小。</p>
 */
@Component
public class ZipBombGuard {

    static final int MAX_ENTRY_COUNT = 1000;
    static final long MAX_ENTRY_BYTES = 50L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 200L * 1024L * 1024L;
    static final long MAX_INFLATE_RATIO = 100L;

    /**
     * 流式扫描整个 ZIP，返回规范化后的条目名集合。
     *
     * @param contentBytes ZIP 原始字节
     * @return 不可修改的条目名集合
     * @throws HealthReportException 容器损坏或命中任一防护上限
     */
    public Set<String> inspect(byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            throw unreadable(null);
        }
        Set<String> entryNameSet = new LinkedHashSet<String>();
        byte[] buffer = new byte[8192];
        long totalBytes = 0L;
        int entryCount = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(contentBytes))) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw unreadable(null);
                }
                String normalizedName = normalizeEntryName(zipEntry.getName());
                if (normalizedName.length() > 0) {
                    entryNameSet.add(normalizedName);
                }
                long entryBytes = 0L;
                int read;
                while ((read = zipInputStream.read(buffer)) != -1) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES
                            || exceedsInflateRatio(totalBytes, contentBytes.length)) {
                        throw unreadable(null);
                    }
                }
                zipInputStream.closeEntry();
            }
        } catch (HealthReportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unreadable(exception);
        }
        if (entryCount == 0) {
            throw unreadable(null);
        }
        return Collections.unmodifiableSet(entryNameSet);
    }

    private boolean exceedsInflateRatio(long totalBytes, int compressedBytes) {
        return compressedBytes <= 0 || totalBytes > (long) compressedBytes * MAX_INFLATE_RATIO;
    }

    private String normalizeEntryName(String entryName) {
        if (entryName == null) {
            return "";
        }
        String normalizedName = entryName.replace('\\', '/');
        while (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        return normalizedName;
    }

    private HealthReportException unreadable(Throwable cause) {
        if (cause == null) {
            return new HealthReportException(FailCode.FILE_UNREADABLE, 400);
        }
        return new HealthReportException(FailCode.FILE_UNREADABLE, 400, cause);
    }
}
