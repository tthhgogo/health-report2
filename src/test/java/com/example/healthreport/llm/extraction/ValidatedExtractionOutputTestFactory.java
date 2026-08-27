package com.example.healthreport.llm.extraction;

import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 为跨包契约测试构造最小已校验输出，不向生产代码开放测试入口。 */
public final class ValidatedExtractionOutputTestFactory {

    private ValidatedExtractionOutputTestFactory() {
    }

    /**
     * 构造不引用任何条目、但内存段映射仍携带完整上游文本的结果，用于验证展示结果不泄漏。
     */
    public static ValidatedExtractionOutput withUnreferencedSourceText(String sourceText) {
        if (sourceText == null) {
            throw new IllegalArgumentException("测试源文本不能为空");
        }
        String segmentId = "f0-p1-s0";
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        segmentByIdMap.put(segmentId, new Segment(segmentId, sourceText, sourceText,
                TextSource.OCR, null));
        return new ValidatedExtractionOutput(
                Collections.<ValidatedExtractionOutput.ReportOverview>emptyList(),
                Collections.<ValidatedExtractionOutput.Section>emptyList(),
                Collections.<ValidatedExtractionOutput.Indicator>emptyList(),
                Collections.<ValidatedExtractionOutput.TextualFinding>emptyList(),
                Collections.<ValidatedExtractionOutput.SummaryConclusion>emptyList(),
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), segmentByIdMap);
    }
}
