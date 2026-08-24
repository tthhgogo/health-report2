package com.example.healthreport.constants;

import lombok.Getter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 过敏误杀例外。
 * <p><b>只作用于菜名字段。</b>食材表里的明确命中永远优先，不可被例外覆盖——
 * 「鱼香肉丝」的例外只取消菜名中「鱼」的命中，该菜配料若另含鱼露，FISH 仍然 REJECT。</p>
 * <p>「部分为」「视配方」这类条件性复合菜名<b>不进本表</b>，它们归 POSSIBLE + MODEL_ONLY
 * ——蟹黄豆腐可能用咸蛋黄也可能真含蟹黄，做成硬白名单就是漏拦。</p>
 * <p>不设条数上限。条数不是安全判据；「例外过多」只作为人工复核指标。</p>
 */
public final class AllergenExceptions {

    private AllergenExceptions() {
    }

    /** 一条例外。 */
    @Getter
    public static final class Rule {

        private final AllergenKey allergenKey;
        /** 被取消命中的那个匹配词 */
        private final String matchWord;
        /** 作用字段，当前只支持菜名 */
        private final SourceField sourceField;
        /** 出现该短语时取消命中 */
        private final String exceptionPhrase;
        private final ReviewStatus reviewStatus;

        Rule(AllergenKey allergenKey, String matchWord, SourceField sourceField,
             String exceptionPhrase, ReviewStatus reviewStatus) {
            this.allergenKey = allergenKey;
            this.matchWord = matchWord;
            this.sourceField = sourceField;
            this.exceptionPhrase = exceptionPhrase;
            this.reviewStatus = reviewStatus;
        }
    }

    /** 全部例外规则。 */
    public static final List<Rule> ALL = Collections.unmodifiableList(Arrays.asList(
            // 鱼香肉丝/鱼香茄子是调味法，不含鱼
            new Rule(AllergenKey.FISH, "鱼", SourceField.DISH_NAME, "鱼香", ReviewStatus.DRAFT),
            // 鱼腥草是植物
            new Rule(AllergenKey.FISH, "鱼", SourceField.DISH_NAME, "鱼腥草", ReviewStatus.DRAFT),
            // 鱿鱼是软体动物，不属鱼类；MOLLUSK 组落地后应由该组接管
            new Rule(AllergenKey.FISH, "鱼", SourceField.DISH_NAME, "鱿鱼", ReviewStatus.DRAFT),
            // 同上
            new Rule(AllergenKey.FISH, "鱼", SourceField.DISH_NAME, "章鱼", ReviewStatus.DRAFT),
            // 同上
            new Rule(AllergenKey.FISH, "鱼", SourceField.DISH_NAME, "墨鱼", ReviewStatus.DRAFT),
            // 蟹味菇是菌菇
            new Rule(AllergenKey.SHRIMP_CRAB, "蟹", SourceField.DISH_NAME, "蟹味菇", ReviewStatus.DRAFT),
            // 蔬菜名
            new Rule(AllergenKey.MILK, "奶", SourceField.DISH_NAME, "奶白菜", ReviewStatus.DRAFT),
            // 植物奶
            new Rule(AllergenKey.MILK, "奶", SourceField.DISH_NAME, "椰奶", ReviewStatus.DRAFT),
            // 植物奶
            new Rule(AllergenKey.MILK, "奶", SourceField.DISH_NAME, "豆奶", ReviewStatus.DRAFT),
            // 植物奶
            new Rule(AllergenKey.MILK, "奶", SourceField.DISH_NAME, "杏仁奶", ReviewStatus.DRAFT),
            // 主料是鸡蛋，不含大豆
            new Rule(AllergenKey.SOY, "豆腐", SourceField.DISH_NAME, "日本豆腐", ReviewStatus.DRAFT),
            // 鱼糜制品，可能不含大豆
            new Rule(AllergenKey.SOY, "豆腐", SourceField.DISH_NAME, "鱼豆腐", ReviewStatus.DRAFT),
            // 杏仁与凝固剂制，不含大豆
            new Rule(AllergenKey.SOY, "豆腐", SourceField.DISH_NAME, "杏仁豆腐", ReviewStatus.DRAFT)
    ));
}
