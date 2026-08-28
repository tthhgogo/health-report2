package com.example.healthreport.safety;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.DegradeAccumulator;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** 校验过敏章节 S、数据行 D、条目证据 A 三个集合的结构与覆盖关系。 */
@Component
public class AllergenCoverageScanner {

    /**
     * D 或 A 不是 S 子集时按模型契约失败；章节无数据或 D 减 A 非空时降级。
     */
    public boolean scan(Set<String> allergenSectionSegmentIdSet,
                        Set<String> allergenDataSegmentIdSet,
                        Set<String> allergenItemSegmentIdSet,
                        DegradeAccumulator degradeAccumulator) {
        if (allergenSectionSegmentIdSet == null || allergenDataSegmentIdSet == null
                || allergenItemSegmentIdSet == null || degradeAccumulator == null) {
            throw new IllegalArgumentException("过敏覆盖扫描参数不能为空");
        }
        if (!allergenSectionSegmentIdSet.containsAll(allergenDataSegmentIdSet)
                || !allergenSectionSegmentIdSet.containsAll(allergenItemSegmentIdSet)) {
            throw new HealthReportException(FailCode.SERVER_ERROR, 500);
        }
        Set<String> uncoveredDataSegmentIdSet =
                new HashSet<String>(allergenDataSegmentIdSet);
        uncoveredDataSegmentIdSet.removeAll(allergenItemSegmentIdSet);
        if ((!allergenSectionSegmentIdSet.isEmpty() && allergenDataSegmentIdSet.isEmpty())
                || !uncoveredDataSegmentIdSet.isEmpty()) {
            degradeAccumulator.recordAllergenSuspectMiss();
            return true;
        }
        return false;
    }
}
