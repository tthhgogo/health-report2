package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对模型声明来自报告原文的短字段做确定性回切校验。
 * <p>本类只做字符串包含与编辑距离，不做版面、表格或医疗语义推断。</p>
 */
@Component
public class SourceEvidenceValidator {

    /** OCR 放宽档比对前移除全部 Unicode 空白：识别结果常在字间插入不存在的空格。 */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /** OCR 放宽档最多容忍一个字符的差异；放宽到两个字就会把不同的指标名判成同一个。 */
    private static final int OCR_MAX_EDIT_DISTANCE = 1;

    private final TextNormalizer textNormalizer;
    private final ExtractionValidationCounters counters;

    public SourceEvidenceValidator(TextNormalizer textNormalizer, ExtractionValidationCounters counters) {
        if (textNormalizer == null || counters == null) {
            throw new IllegalArgumentException("来源校验依赖不能为空");
        }
        this.textNormalizer = textNormalizer;
        this.counters = counters;
    }

    /**
     * 校验一个短字段是否能在证据段合并文本中回切。
     *
     * @return 严格或 OCR 放宽匹配成功时为 true；证据不存在时为 false
     */
    public boolean matches(String fieldValue, List<String> segmentIdList,
                           Map<String, Segment> segmentByIdMap) {
        if (fieldValue == null || segmentIdList == null || segmentIdList.isEmpty()
                || segmentByIdMap == null) {
            return false;
        }
        StringBuilder evidenceBuilder = new StringBuilder();
        boolean containsOcr = false;
        for (String segmentId : segmentIdList) {
            Segment segment = segmentByIdMap.get(segmentId);
            if (segment == null) {
                return false;
            }
            evidenceBuilder.append(segment.getNormalizedText());
            if (segment.getTextSource() == TextSource.OCR) {
                containsOcr = true;
            }
        }
        String normalizedField = textNormalizer.normalize(fieldValue).getNormalizedText();
        String normalizedEvidence = evidenceBuilder.toString();
        if (!containsOcr) {
            return normalizedEvidence.contains(normalizedField);
        }
        String compactField = removeWhitespace(normalizedField);
        String compactEvidence = removeWhitespace(normalizedEvidence);
        if (compactEvidence.contains(compactField)) {
            return true;
        }
        if (containsSubstringWithinOneEdit(compactEvidence, compactField)) {
            counters.recordOcrFuzzyMatch();
            return true;
        }
        return false;
    }

    private String removeWhitespace(String value) {
        return WHITESPACE_PATTERN.matcher(value).replaceAll("");
    }

    /** 在证据文本的任意连续窗口上判定编辑距离，避免拿短字段和整段长文本直接比较。 */
    private boolean containsSubstringWithinOneEdit(String evidence, String field) {
        if (field.length() <= OCR_MAX_EDIT_DISTANCE) {
            // 单字符字段允许一次编辑会与任意字符甚至空窗口匹配，失去来源约束，只保留前置子串校验。
            return false;
        }
        int minimumWindowLength = Math.max(1, field.length() - OCR_MAX_EDIT_DISTANCE);
        int maximumWindowLength = Math.min(evidence.length(), field.length() + OCR_MAX_EDIT_DISTANCE);
        for (int windowLength = minimumWindowLength; windowLength <= maximumWindowLength; windowLength++) {
            for (int start = 0; start + windowLength <= evidence.length(); start++) {
                if (editDistanceAtMostOne(field, evidence, start, windowLength)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 专用于阈值一的编辑距离判断，超过阈值立即结束。 */
    private boolean editDistanceAtMostOne(String left, String right, int rightStart, int rightLength) {
        int lengthDifference = left.length() - rightLength;
        if (Math.abs(lengthDifference) > OCR_MAX_EDIT_DISTANCE) {
            return false;
        }
        int leftIndex = 0;
        int rightIndex = rightStart;
        int rightEnd = rightStart + rightLength;
        int editCount = 0;
        while (leftIndex < left.length() && rightIndex < rightEnd) {
            if (left.charAt(leftIndex) == right.charAt(rightIndex)) {
                leftIndex++;
                rightIndex++;
                continue;
            }
            editCount++;
            if (editCount > OCR_MAX_EDIT_DISTANCE) {
                return false;
            }
            if (left.length() > rightLength) {
                leftIndex++;
            } else if (left.length() < rightLength) {
                rightIndex++;
            } else {
                leftIndex++;
                rightIndex++;
            }
        }
        if (leftIndex < left.length() || rightIndex < rightEnd) {
            editCount++;
        }
        return editCount <= OCR_MAX_EDIT_DISTANCE;
    }
}
