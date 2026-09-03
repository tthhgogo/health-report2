package com.example.healthreport.constants;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 过敏原组。展示与 Layer 1 共用同一份数据，避免「展示了但不匹配」或「匹配了但不展示」。
 */
@Getter
public final class AllergenGroup {

    private final AllergenKey key;
    private final String displayName;
    private final List<AllergenWord> wordList;

    // 是否食入性不再是字段（设计方案 §7.2）：由 AllergenGroups.FOOD_BORNE_KEYS 的
    // 成员关系判断，避免同一事实存两处、改一处漏一处。
    AllergenGroup(AllergenKey key, String displayName, List<AllergenWord> wordList) {
        this.key = key;
        this.displayName = displayName;
        this.wordList = Collections.unmodifiableList(wordList);
    }
}
