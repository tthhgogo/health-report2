package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.Segment;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 为单批页面建立连续块号，并持有块号到进程内 segmentId 的映射。
 * <p>模型只看到块号；映射不进入请求，也不持久化。</p>
 */
public final class BatchAddressing {

    @Getter
    private final List<String> segmentIdByBlockRef;
    @Getter
    private final List<BatchPage> pageList;

    public BatchAddressing(int fileIndex, List<ParsedPage> parsedPageList) {
        if (fileIndex < 0 || fileIndex > 4 || parsedPageList == null || parsedPageList.isEmpty()) {
            throw new IllegalArgumentException("批次编址参数无效");
        }
        int segmentCount = 0;
        for (ParsedPage parsedPage : parsedPageList) {
            if (parsedPage != null) {
                segmentCount += parsedPage.getSegmentList().size();
            }
        }
        List<String> mappingList = new ArrayList<String>(segmentCount);
        List<BatchPage> addressedPageList = new ArrayList<BatchPage>(parsedPageList.size());
        int previousPage = 0;
        for (ParsedPage parsedPage : parsedPageList) {
            if (parsedPage == null || parsedPage.getPage() <= previousPage) {
                throw new IllegalArgumentException("批次页码必须严格递增");
            }
            List<Segment> orderedSegmentList = new ArrayList<Segment>(parsedPage.getSegmentList());
            Collections.sort(orderedSegmentList, new Comparator<Segment>() {
                @Override
                public int compare(Segment left, Segment right) {
                    return Integer.compare(sequence(left), sequence(right));
                }
            });
            assertSegmentOrder(fileIndex, parsedPage.getPage(), orderedSegmentList);
            String renderedText = renderPage(parsedPage.getPage(), orderedSegmentList, mappingList);
            addressedPageList.add(new BatchPage(parsedPage.getPage(), renderedText,
                    parsedPage.getJpegBytes(), parsedPage.isImageRequired()));
            previousPage = parsedPage.getPage();
        }
        this.segmentIdByBlockRef = Collections.unmodifiableList(mappingList);
        this.pageList = Collections.unmodifiableList(addressedPageList);
    }

    /** 把模型返回的批内块号展开为进程内 segmentId。 */
    public String expand(int blockRef) {
        if (blockRef < 0 || blockRef >= segmentIdByBlockRef.size()) {
            throw new IllegalArgumentException("blockRef 越界");
        }
        return segmentIdByBlockRef.get(blockRef);
    }

    private String renderPage(int page, List<Segment> segmentList, List<String> mappingList) {
        StringBuilder rendered = new StringBuilder(64 + segmentList.size() * 64);
        rendered.append("=== 第 ").append(page).append(" 页 ===\n");
        for (Segment segment : segmentList) {
            int blockRef = mappingList.size();
            mappingList.add(segment.getSegmentId());
            rendered.append('[').append(blockRef).append("] (")
                    .append(segment.getTextSource().name()).append(", bbox=")
                    .append(formatBbox(segment.getBbox())).append(") ")
                    .append(segment.getNormalizedText()).append('\n');
        }
        return rendered.toString();
    }

    private String formatBbox(BBox bbox) {
        if (bbox == null) {
            return "null";
        }
        return decimal(bbox.getX()) + "," + decimal(bbox.getY()) + ","
                + decimal(bbox.getWidth()) + "," + decimal(bbox.getHeight());
    }

    private String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void assertSegmentOrder(int fileIndex, int page, List<Segment> segmentList) {
        int previousSequence = -1;
        String prefix = "f" + fileIndex + "-p" + page + "-s";
        for (Segment segment : segmentList) {
            if (segment == null || !segment.getSegmentId().startsWith(prefix)) {
                throw new IllegalArgumentException("segmentId 与文件页码不一致");
            }
            int currentSequence = sequence(segment);
            if (currentSequence <= previousSequence) {
                throw new IllegalArgumentException("页内 segment seq 不能重复");
            }
            previousSequence = currentSequence;
        }
    }

    private static int sequence(Segment segment) {
        String segmentId = segment.getSegmentId();
        int marker = segmentId.lastIndexOf("-s");
        return Integer.parseInt(segmentId.substring(marker + 2));
    }
}
