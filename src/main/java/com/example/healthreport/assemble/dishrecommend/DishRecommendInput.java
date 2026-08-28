package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.TagState;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 模块四组装输入；生产实例由 {@link DishRecommendInputFactory} 完成三类标签事实的合并。 */
@Getter
public final class DishRecommendInput {

    private final boolean suppressDishRecommend;
    private final boolean formalAdvicePresent;
    private final List<Candidate> candidateList;

    /** 创建模块四输入；抑制开关为真时组装器不读取候选内容。 */
    public DishRecommendInput(boolean suppressDishRecommend, boolean formalAdvicePresent,
                        List<Candidate> candidateList) {
        if (candidateList == null) {
            throw new IllegalArgumentException("候选菜品不能为空");
        }
        this.suppressDishRecommend = suppressDishRecommend;
        this.formalAdvicePresent = formalAdvicePresent;
        this.candidateList = Collections.unmodifiableList(new ArrayList<Candidate>(candidateList));
    }

    /** 一道菜及其全部维度事实；必须先完整裁决后才能参与截断。 */
    @Getter
    public static final class Candidate {
        private final Dish dish;
        private final List<Match> matchList;

        public Candidate(Dish dish, List<Match> matchList) {
            if (dish == null || matchList == null) {
                throw new IllegalArgumentException("菜品候选字段不能为空");
            }
            this.dish = dish;
            this.matchList = Collections.unmodifiableList(new ArrayList<Match>(matchList));
        }
    }

    /** 一个维度用于裁决和展示的确定性事实。 */
    @Getter
    public static final class Match {
        private final TagState state;
        private final boolean rejectCapable;
        private final boolean allergy;
        private final TagType tagType;
        private final String tagText;
        private final List<String> matchedIngredientList;
        private final String rawText;

        public Match(TagState state, boolean rejectCapable, boolean allergy, TagType tagType,
                     String tagText, List<String> matchedIngredientList, String rawText) {
            if (state == null || tagType == null || tagText == null
                    || matchedIngredientList == null) {
                throw new IllegalArgumentException("维度匹配字段不能为空");
            }
            if (state == TagState.RECOMMEND
                    && (matchedIngredientList.isEmpty() || rawText == null
                    || rawText.length() == 0)) {
                throw new IllegalArgumentException("推荐维度必须携带命中食材和报告原文");
            }
            this.state = state;
            this.rejectCapable = rejectCapable;
            this.allergy = allergy;
            this.tagType = tagType;
            this.tagText = tagText;
            this.matchedIngredientList = Collections.unmodifiableList(
                    new ArrayList<String>(matchedIngredientList));
            this.rawText = rawText;
        }
    }

    /** 模块四标签类型。 */
    public enum TagType {

        /** 营养补充正面标签。 */
        NUTRITION,

        /** 过敏拒绝标签。 */
        ALLERGY,

        /** 饮食注意拒绝标签。 */
        DIET_AVOID,

        /** 饮食注意正面标签，由 Java 确定性主料交集产出，与 LLM-B 的安全结论无关。 */
        DIET_OK
    }
}
