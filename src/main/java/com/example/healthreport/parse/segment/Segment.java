package com.example.healthreport.parse.segment;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.regex.Pattern;

/**
 * 任务执行期内存中的不可变原子文本块。
 * <p>原文与规范化文本分离；本对象不落库、不落盘、不进入 Redis。</p>
 */
@Getter
@EqualsAndHashCode
@ToString(exclude = {"rawText", "normalizedText"})
public final class Segment {

    private static final Pattern SEGMENT_ID_PATTERN = Pattern.compile("f[0-4]-p[1-9][0-9]*-s[0-9]+");

    private final String segmentId;
    private final String rawText;
    private final String normalizedText;
    private final TextSource textSource;
    private final BBox bbox;

    public Segment(String segmentId, String rawText, String normalizedText,
                   TextSource textSource, BBox bbox) {
        if (segmentId == null || !SEGMENT_ID_PATTERN.matcher(segmentId).matches()) {
            throw new IllegalArgumentException("segmentId 格式无效");
        }
        if (rawText == null || normalizedText == null || textSource == null) {
            throw new IllegalArgumentException("segment 文本与来源不能为空");
        }
        this.segmentId = segmentId;
        this.rawText = rawText;
        this.normalizedText = normalizedText;
        this.textSource = textSource;
        this.bbox = bbox;
    }

    /**
     * 从 segmentId 反解本块所属页码。
     * <p>页码不另存字段：多存一份就多一处可能与 segmentId 不一致的真源。
     * 格式由 {@link #SEGMENT_ID_PATTERN} 在构造时保证，这里不会解析失败。</p>
     */
    public int pageNumber() {
        int pageStart = segmentId.indexOf("-p") + 2;
        return Integer.parseInt(segmentId.substring(pageStart, segmentId.indexOf("-s", pageStart)));
    }

    /** 按固定格式生成进程内主键。 */
    public static String id(int fileIndex, int page, int sequence) {
        if (fileIndex < 0 || fileIndex > 4 || page < 1 || sequence < 0) {
            throw new IllegalArgumentException("segment 编址参数越界");
        }
        return "f" + fileIndex + "-p" + page + "-s" + sequence;
    }
}
