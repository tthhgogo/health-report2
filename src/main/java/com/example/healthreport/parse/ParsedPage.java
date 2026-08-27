package com.example.healthreport.parse;

import com.example.healthreport.parse.segment.Segment;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个真实页或 Word 逻辑页的内存态解析结果。
 * <p>只保存已经编码的 JPEG 字节，不持有 {@code BufferedImage}，也不落盘或持久化。</p>
 */
@Getter
@ToString(exclude = {"segmentList", "jpegBytes"})
public final class ParsedPage {

    private final int page;
    private final List<Segment> segmentList;
    private final byte[] jpegBytes;
    private final boolean imageRequired;

    public ParsedPage(int page, List<Segment> segmentList, byte[] jpegBytes, boolean imageRequired) {
        if (page < 1 || segmentList == null) {
            throw new IllegalArgumentException("解析页参数无效");
        }
        if (imageRequired && jpegBytes == null) {
            throw new IllegalArgumentException("需要页面图时 JPEG 不能为空");
        }
        this.page = page;
        this.segmentList = Collections.unmodifiableList(new ArrayList<Segment>(segmentList));
        this.jpegBytes = jpegBytes;
        this.imageRequired = imageRequired;
    }
}
