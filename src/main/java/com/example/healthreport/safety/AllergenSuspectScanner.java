package com.example.healthreport.safety;

import com.example.healthreport.llm.extraction.ExtractionValidationCounters;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.task.DegradeAccumulator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 当模型没有返回任何有效过敏条目时，对全部原子段做高风险交叉扫描。
 * <p>阳性标记只在模型圈定的过敏章节段内判断；本扫描不做相邻块或坐标配对。</p>
 */
@Component
public class AllergenSuspectScanner {

    private final ExtractionValidationCounters counters;

    public AllergenSuspectScanner(ExtractionValidationCounters counters) {
        if (counters == null) {
            throw new IllegalArgumentException("过敏扫描计数器不能为空");
        }
        this.counters = counters;
    }

    /** 模型过敏数组为空且命中章节或章节内阳性标记时，幂等记录任务降级。 */
    public boolean scan(List<Segment> allSegmentList, Set<String> allergenSectionSegmentIdSet,
                        int sourceValidatedAllergenCount, DegradeAccumulator degradeAccumulator) {
        if (allSegmentList == null || allergenSectionSegmentIdSet == null
                || degradeAccumulator == null) {
            throw new IllegalArgumentException("过敏扫描参数不能为空");
        }
        if (sourceValidatedAllergenCount > 0) {
            return false;
        }
        boolean sectionTermFound = false;
        boolean positiveInAllergenSection = false;
        for (Segment segment : allSegmentList) {
            String normalizedText = segment.getNormalizedText();
            if (containsAny(normalizedText, AllergenSafetyTerms.SECTION_TERM_LIST)) {
                sectionTermFound = true;
            }
            if (allergenSectionSegmentIdSet.contains(segment.getSegmentId())
                    && containsAny(normalizedText, AllergenSafetyTerms.ADMITTED_RESULT_MARK_LIST)) {
                positiveInAllergenSection = true;
            }
        }
        if (sectionTermFound || positiveInAllergenSection) {
            degradeAccumulator.recordAllergenSuspectMiss();
            counters.recordAllergenSuspectMiss();
            return true;
        }
        return false;
    }

    private boolean containsAny(String text, List<String> termList) {
        for (String term : termList) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
