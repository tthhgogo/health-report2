package com.example.healthreport.infra;

import java.io.OutputStream;
import java.util.Arrays;

/**
 * 自管数组且容量硬封顶的字节输出流。
 * <p>底层数组永不超过 {@code maxBytes}，写入越界立即失败。</p>
 */
public class CappedByteArrayOutputStream extends OutputStream {

    private byte[] buffer;
    private int count;
    private final int maxBytes;

    public CappedByteArrayOutputStream(int initialCapacity, int maxBytes) {
        if (initialCapacity < 0 || maxBytes < 1) {
            throw new IllegalArgumentException("缓冲区容量参数无效");
        }
        this.maxBytes = maxBytes;
        this.buffer = new byte[Math.max(1, Math.min(initialCapacity, maxBytes))];
    }

    @Override
    public void write(int value) {
        ensureCapacity((long) count + 1L);
        buffer[count++] = (byte) value;
    }

    @Override
    public void write(byte[] source, int offset, int length) {
        if (source == null) {
            throw new NullPointerException("source");
        }
        if (offset < 0 || length < 0 || offset > source.length - length) {
            throw new IndexOutOfBoundsException("写入范围越界");
        }
        ensureCapacity((long) count + length);
        System.arraycopy(source, offset, buffer, count, length);
        count += length;
    }

    /** 当前已写入字节数。 */
    public int size() {
        return count;
    }

    /** 当前底层数组容量，供容量边界测试使用。 */
    public int capacity() {
        return buffer.length;
    }

    /** 返回恰好等于已写入长度的副本。 */
    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, count);
    }

    private void ensureCapacity(long required) {
        if (required > maxBytes) {
            throw new RequestTooLargeException(required, maxBytes);
        }
        if (required <= buffer.length) {
            return;
        }
        int newCapacity = (int) Math.min(
                Math.max(required, (long) buffer.length * 2L), maxBytes);
        buffer = Arrays.copyOf(buffer, newCapacity);
    }
}
