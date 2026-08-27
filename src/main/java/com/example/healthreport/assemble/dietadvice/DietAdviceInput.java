package com.example.healthreport.assemble.dietadvice;

import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块三专用输入。
 * <p>结构中只有建议枚举、报告原文和机械来源字段，没有指标列表、指标值或健康问题，
 * 从类型边界上阻止由指标异常推导饮食建议。</p>
 */
@Getter
public final class DietAdviceInput {

    private final List<AllergenItem> allergenList;
    private final List<AdviceItem<NutritionKey>> nutritionList;
    private final List<AdviceItem<DietRequirementKey>> dietList;

    DietAdviceInput(List<AllergenItem> allergenList,
                 List<AdviceItem<NutritionKey>> nutritionList,
                 List<AdviceItem<DietRequirementKey>> dietList) {
        this.allergenList = immutableList(allergenList);
        this.nutritionList = immutableList(nutritionList);
        this.dietList = immutableList(dietList);
    }

    private static <T> List<T> immutableList(List<T> sourceList) {
        return Collections.unmodifiableList(new ArrayList<T>(sourceList));
    }

    /** 结构化判断可见的最小值对象：只有模型枚举和报告原文。 */
    @Getter
    public static final class StructuredValue<T extends Enum<T>> {
        private final T enumKey;
        private final List<String> rawTextList;

        StructuredValue(T enumKey, List<String> rawTextList) {
            this.enumKey = enumKey;
            this.rawTextList = immutableList(rawTextList);
        }
    }

    /** 机械来源标注，已由有效章节名和可选报告条号拼出。 */
    @Getter
    public static final class Source {
        private final String sourceLabel;

        Source(String sourceLabel) {
            this.sourceLabel = sourceLabel;
        }
    }

    /** 过敏提醒输入；名称与结果均为报告原文字段。 */
    @Getter
    public static final class AllergenItem {
        private final StructuredValue<AllergenKey> structuredValue;
        private final Source source;
        private final boolean foodBorne;
        private final String rawName;
        private final String rawResult;

        AllergenItem(StructuredValue<AllergenKey> structuredValue, Source source,
                     boolean foodBorne, String rawName, String rawResult) {
            this.structuredValue = structuredValue;
            this.source = source;
            this.foodBorne = foodBorne;
            this.rawName = rawName;
            this.rawResult = rawResult;
        }
    }

    /** 营养补充或饮食注意输入，不包含任何指标数据。 */
    @Getter
    public static final class AdviceItem<T extends Enum<T>> {
        private final StructuredValue<T> structuredValue;
        private final Source source;

        AdviceItem(StructuredValue<T> structuredValue, Source source) {
            this.structuredValue = structuredValue;
            this.source = source;
        }
    }
}
