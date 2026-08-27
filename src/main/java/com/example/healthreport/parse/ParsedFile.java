package com.example.healthreport.parse;

import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单文件解析完成后的统一内存模型。
 * <p>非 Word 的等效页数直接采用上传期 {@code precheckPages}；Word 每四十个有序块形成逻辑页。</p>
 */
@Getter
@ToString(exclude = "pageList")
public final class ParsedFile {

    public static final int WORD_SEGMENTS_PER_PAGE = 40;

    private final int fileIndex;
    private final ContentType contentType;
    private final int effectivePageCount;
    private final List<ParsedPage> pageList;

    public ParsedFile(int fileIndex, ContentType contentType, int precheckPages, List<ParsedPage> pageList) {
        if (fileIndex < 0 || fileIndex > 4 || contentType == null || pageList == null) {
            throw new IllegalArgumentException("文件解析结果参数无效");
        }
        if (isWord(contentType)) {
            throw new IllegalArgumentException("Word 必须通过 word 工厂按块构造逻辑页");
        }
        if (precheckPages < 1 || pageList.size() != precheckPages) {
            throw new IllegalArgumentException("非 Word 页数必须与 precheckPages 一致");
        }
        validatePageOrder(pageList);
        this.fileIndex = fileIndex;
        this.contentType = contentType;
        this.effectivePageCount = precheckPages;
        this.pageList = immutablePages(pageList);
    }

    private ParsedFile(int fileIndex, ContentType contentType, List<ParsedPage> pageList) {
        this.fileIndex = fileIndex;
        this.contentType = contentType;
        this.effectivePageCount = pageList.size();
        this.pageList = immutablePages(pageList);
    }

    /**
     * 把 Word 的有序文本块按每四十块切成逻辑页；Word 不向 LLM-A 发送页面图。
     */
    public static ParsedFile word(int fileIndex, ContentType contentType, List<Segment> segmentList) {
        if (fileIndex < 0 || fileIndex > 4 || !isWord(contentType) || segmentList == null) {
            throw new IllegalArgumentException("Word 解析结果参数无效");
        }
        if (segmentList.size() > 1200) {
            throw new HealthReportException(FailCode.PAGE_LIMIT_EXCEEDED, 400);
        }
        List<ParsedPage> pageList = new ArrayList<ParsedPage>(
                (segmentList.size() + WORD_SEGMENTS_PER_PAGE - 1) / WORD_SEGMENTS_PER_PAGE);
        for (int from = 0; from < segmentList.size(); from += WORD_SEGMENTS_PER_PAGE) {
            int to = Math.min(from + WORD_SEGMENTS_PER_PAGE, segmentList.size());
            pageList.add(new ParsedPage(pageList.size() + 1,
                    segmentList.subList(from, to), null, false));
        }
        return new ParsedFile(fileIndex, contentType, pageList);
    }

    /** 返回本文件是否有任何可送模型的文字块。 */
    public boolean hasSegments() {
        for (ParsedPage page : pageList) {
            if (!page.getSegmentList().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 本文件全部页的文字块总数，供解析日志描述产出规模；不触碰块内容。 */
    public int segmentCount() {
        int total = 0;
        for (ParsedPage page : pageList) {
            total += page.getSegmentList().size();
        }
        return total;
    }

    /** 创建仅保留前若干等效页的视图，文件内真实页码保持不变。 */
    public ParsedFile retainFirstPages(int retainedPages) {
        if (retainedPages < 0 || retainedPages > pageList.size()) {
            throw new IllegalArgumentException("保留页数越界");
        }
        return new ParsedFile(fileIndex, contentType,
                new ArrayList<ParsedPage>(pageList.subList(0, retainedPages)));
    }

    /** 判断是否为 Word 格式。 */
    public boolean isWord() {
        return isWord(contentType);
    }

    private static boolean isWord(ContentType contentType) {
        return contentType == ContentType.DOC || contentType == ContentType.DOCX;
    }

    private static List<ParsedPage> immutablePages(List<ParsedPage> pageList) {
        return Collections.unmodifiableList(new ArrayList<ParsedPage>(pageList));
    }

    private static void validatePageOrder(List<ParsedPage> pageList) {
        int previous = 0;
        for (ParsedPage page : pageList) {
            if (page == null || page.getPage() <= previous) {
                throw new IllegalArgumentException("文件内页码必须严格递增");
            }
            previous = page.getPage();
        }
    }
}
