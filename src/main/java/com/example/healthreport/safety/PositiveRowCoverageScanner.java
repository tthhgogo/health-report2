package com.example.healthreport.safety;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.task.DegradeAccumulator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检查同一原子段内同时出现结果标记和已知过敏原名的候选是否被模型覆盖。
 * <p>这是有限兜底，明确不跨段使用 bbox、seq 或表格还原配对。</p>
 */
@Component
public class PositiveRowCoverageScanner {

    private final List<String> knownAllergenNameList;

    public PositiveRowCoverageScanner() {
        this.knownAllergenNameList = knownAllergenNames();
    }

    /** 统计未被 A 集合覆盖的同块候选数，命中时触发一次任务级降级。 */
    public int scan(List<Segment> allSegmentList, Set<String> admittedSegmentIdSet,
                    DegradeAccumulator degradeAccumulator) {
        if (allSegmentList == null || admittedSegmentIdSet == null || degradeAccumulator == null) {
            throw new IllegalArgumentException("阳性覆盖扫描参数不能为空");
        }
        int uncoveredCount = 0;
        for (Segment segment : allSegmentList) {
            String normalizedText = segment.getNormalizedText();
            if (containsAny(normalizedText, AllergenSafetyTerms.ADMITTED_RESULT_MARK_LIST)
                    && containsAny(normalizedText, knownAllergenNameList)
                    && !admittedSegmentIdSet.contains(segment.getSegmentId())) {
                uncoveredCount++;
            }
        }
        if (uncoveredCount > 0) {
            degradeAccumulator.recordAllergenSuspectMiss();
        }
        return uncoveredCount;
    }

    private List<String> knownAllergenNames() {
        Set<String> nameSet = new LinkedHashSet<String>();
        for (AllergenGroup group : AllergenGroups.ALL.values()) {
            nameSet.add(group.getDisplayName());
            for (AllergenWord word : group.getWordList()) {
                nameSet.add(word.getMatchWord());
            }
        }
        return new ArrayList<String>(nameSet);
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
