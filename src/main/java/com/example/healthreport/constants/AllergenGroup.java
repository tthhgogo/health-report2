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
    /** false 表示非食物过敏原（尘螨、花粉等），只展示，不进菜品链路 */
    private final boolean foodBorne;
    private final List<AllergenWord> wordList;

    AllergenGroup(AllergenKey key, String displayName, boolean foodBorne, List<AllergenWord> wordList) {
        this.key = key;
        this.displayName = displayName;
        this.foodBorne = foodBorne;
        this.wordList = Collections.unmodifiableList(wordList);
    }
}
