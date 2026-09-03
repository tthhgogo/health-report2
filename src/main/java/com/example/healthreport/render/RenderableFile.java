package com.example.healthreport.render;

/**
 * 待转图的单个文件来源：序号、真实格式与按需读取的字节。
 *
 * <p><b>字节必须按需读取</b>：转图编排逐文件调用 {@link #readContentBytes()}，
 * 渲染完该文件后引用即出作用域。调用方不得预读全部文件——
 * 5 份 × 20MB 的原文件同驻内存，加上压缩页与请求体副本就是数百 MB 堆峰值。</p>
 */
public interface RenderableFile {

    /** 文件在任务内的顺序，从 0 起。 */
    int getFileIndex();

    /** 按内容判定的真实格式。 */
    ContentType getContentType();

    /** 即时读取完整文件字节；每次调用重新读取，实现方与调用方都不得缓存。 */
    byte[] readContentBytes();
}
