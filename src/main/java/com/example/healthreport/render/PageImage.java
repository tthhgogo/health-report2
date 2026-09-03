package com.example.healthreport.render;

/**
 * 一页发给体检报告分析模型的 JPEG 页面图。
 *
 * <p><b>字节数组不做防御性复制。</b> 一个任务最多 30 页、每页最大 1MiB，三次调用共用同一份；
 * 逐处复制会把内存峰值翻倍。构造后本类持有该数组的所有权，调用方不得再修改传入数组，
 * {@link #getJpegBytes()} 返回的也是内部引用，消费方只读。</p>
 */
public final class PageImage {

    private final int page;
    private final byte[] jpegBytes;

    public PageImage(int page, byte[] jpegBytes) {
        if (page < 1 || jpegBytes == null || jpegBytes.length == 0) {
            throw new IllegalArgumentException("页面图内容必须有效");
        }
        this.page = page;
        this.jpegBytes = jpegBytes;
    }

    /** 全局图序号，从 1 起。 */
    public int getPage() {
        return page;
    }

    /** 压缩档 JPEG 字节；内部引用，消费方只读。 */
    public byte[] getJpegBytes() {
        return jpegBytes;
    }
}
