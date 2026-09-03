package com.example.healthreport.render.image;

/**
 * 从 JPEG 字节中读取 EXIF Orientation（TIFF 标签 0x0112）。
 *
 * <p>确定性字节扫描，不引第三方元数据库。任何解析不确定（无 APP1、无 EXIF 头、
 * 结构越界、值不在 1~8）一律返回 1（不变换）——方向读不出来时按原样发图，
 * 比按猜出来的方向旋转安全。PNG 无 EXIF，调用方直接按 1 处理。</p>
 */
public final class ExifOrientationReader {

    private ExifOrientationReader() {
    }

    /** 返回 1~8 的 Orientation；解析不出时返回 1。 */
    public static int read(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length < 4
                || (jpegBytes[0] & 0xFF) != 0xFF || (jpegBytes[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 <= jpegBytes.length) {
            if ((jpegBytes[offset] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = jpegBytes[offset + 1] & 0xFF;
            // SOS 之后是压缩数据，EXIF 不会再出现。
            if (marker == 0xDA) {
                return 1;
            }
            int segmentLength = ((jpegBytes[offset + 2] & 0xFF) << 8) | (jpegBytes[offset + 3] & 0xFF);
            if (segmentLength < 2 || offset + 2 + segmentLength > jpegBytes.length) {
                return 1;
            }
            if (marker == 0xE1) {
                int orientation = readFromApp1(jpegBytes, offset + 4, segmentLength - 2);
                if (orientation != 0) {
                    return orientation;
                }
            }
            offset += 2 + segmentLength;
        }
        return 1;
    }

    /** 解析 APP1 段；不是 EXIF 或解析失败返回 0。 */
    private static int readFromApp1(byte[] bytes, int start, int length) {
        // "Exif\0\0" 前缀 + 至少 TIFF 头 8 字节。
        if (length < 14
                || bytes[start] != 'E' || bytes[start + 1] != 'x'
                || bytes[start + 2] != 'i' || bytes[start + 3] != 'f'
                || bytes[start + 4] != 0 || bytes[start + 5] != 0) {
            return 0;
        }
        int tiffStart = start + 6;
        int tiffEnd = start + length;
        boolean littleEndian;
        if (bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return 0;
        }
        long ifdOffset = readUint32(bytes, tiffStart + 4, littleEndian);
        long entryCountPosition = tiffStart + ifdOffset;
        if (ifdOffset < 8L || entryCountPosition + 2L > tiffEnd) {
            return 0;
        }
        int entryCount = readUint16(bytes, (int) entryCountPosition, littleEndian);
        long entryPosition = entryCountPosition + 2L;
        for (int index = 0; index < entryCount; index++) {
            long position = entryPosition + index * 12L;
            if (position + 12L > tiffEnd) {
                return 0;
            }
            int tag = readUint16(bytes, (int) position, littleEndian);
            if (tag == 0x0112) {
                int value = readUint16(bytes, (int) position + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : 0;
            }
        }
        return 0;
    }

    private static int readUint16(byte[] bytes, int position, boolean littleEndian) {
        int first = bytes[position] & 0xFF;
        int second = bytes[position + 1] & 0xFF;
        return littleEndian ? (second << 8) | first : (first << 8) | second;
    }

    private static long readUint32(byte[] bytes, int position, boolean littleEndian) {
        long b0 = bytes[position] & 0xFFL;
        long b1 = bytes[position + 1] & 0xFFL;
        long b2 = bytes[position + 2] & 0xFFL;
        long b3 = bytes[position + 3] & 0xFFL;
        return littleEndian
                ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
                : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}
