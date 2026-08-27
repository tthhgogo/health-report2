package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.dish.DishDisposition;
import com.example.healthreport.dish.TagState;
import com.example.healthreport.dish.TagStateResolver;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 模块四五态裁决、标签净化、拼音排序与最终截断的唯一组装器。 */
@Service
public class DishRecommendAssembler {

    /** 推荐与不推荐列表各最多展示三道菜。 */
    private static final int MAX_DISPLAY_DISHES = 3;

    /** 推荐理由中的食材连接符，保持原食材名不改写。 */
    private static final String INGREDIENT_SEPARATOR = "、";

    private final TagStateResolver tagStateResolver;
    private final DisplayOrder displayOrder;

    public DishRecommendAssembler(TagStateResolver tagStateResolver, DisplayOrder displayOrder) {
        this.tagStateResolver = tagStateResolver;
        this.displayOrder = displayOrder;
    }

    /**
     * 先对全部候选完成维度裁决，再分别排序并截断；抑制时整个模块返回 {@code null}。
     */
    public Result assemble(DishRecommendInput input) {
        if (input == null) {
            throw new IllegalArgumentException("模块四输入不能为空");
        }
        if (input.isSuppressDishRecommend()) {
            return null;
        }
        List<DishCard> recommendCardList = new ArrayList<DishCard>();
        List<DishCard> rejectCardList = new ArrayList<DishCard>();
        for (DishRecommendInput.Candidate candidate : input.getCandidateList()) {
            DishDisposition disposition = tagStateResolver.resolve(facts(candidate.getMatchList()));
            if (disposition == DishDisposition.RECOMMENDED) {
                recommendCardList.add(recommendedCard(candidate));
            } else if (disposition == DishDisposition.NOT_RECOMMENDED) {
                rejectCardList.add(rejectedCard(candidate));
            }
        }
        recommendCardList = displayOrder.sortDishByPinyin(recommendCardList);
        rejectCardList = displayOrder.sortDishByPinyin(rejectCardList);
        recommendCardList = truncate(recommendCardList);
        rejectCardList = truncate(rejectCardList);

        // 【OTHER 过敏原命中不算「有个性化推荐」】（2026-08-26 产品确认）
        // 此时会同时出现一张「{原文名}过敏」的不推荐卡片和「本食堂菜品暂无个性化推荐。」，
        // 产品判定这样展示没有问题：卡片说的是「这道菜你不能吃」，空态说的是
        // 「没有为你挑出可推荐的菜」，两句并不冲突。formalAdvicePresent 只统计正式枚举。
        String emptyState = null;
        if (!input.isFormalAdvicePresent()) {
            emptyState = EmptyStateConstants.MODULE_FOUR_NO_ADVICE;
        } else if (recommendCardList.isEmpty() && rejectCardList.isEmpty()) {
            emptyState = EmptyStateConstants.MODULE_FOUR_NO_MATCH;
        }
        return new Result(recommendCardList, rejectCardList, emptyState,
                DisclaimerConstants.MODULE_FOUR);
    }

    private List<TagStateResolver.Fact> facts(List<DishRecommendInput.Match> matchList) {
        List<TagStateResolver.Fact> factList = new ArrayList<TagStateResolver.Fact>(matchList.size());
        for (DishRecommendInput.Match match : matchList) {
            factList.add(new TagStateResolver.Fact(match.getState(), match.isRejectCapable(),
                    match.isAllergy()));
        }
        return factList;
    }

    private DishCard recommendedCard(DishRecommendInput.Candidate candidate) {
        List<Tag> tagList = new ArrayList<Tag>();
        List<String> reasonList = new ArrayList<String>();
        for (DishRecommendInput.Match match : candidate.getMatchList()) {
            if (match.getState() == TagState.RECOMMEND) {
                tagList.add(new Tag(match.getTagType(), match.getTagText()));
                reasonList.add(reason(candidate.getDish().getDishName(), match));
            }
        }
        return new DishCard(candidate.getDish().getDishId(), candidate.getDish().getDishName(),
                tagList, Collections.<Tag>emptyList(), reasonList);
    }

    private DishCard rejectedCard(DishRecommendInput.Candidate candidate) {
        boolean allergyRejected = false;
        for (DishRecommendInput.Match match : candidate.getMatchList()) {
            if (match.isAllergy() && match.getState() == TagState.REJECT) {
                allergyRejected = true;
                break;
            }
        }
        List<Tag> tagList = new ArrayList<Tag>();
        List<Tag> supplementalTagList = new ArrayList<Tag>();
        for (DishRecommendInput.Match match : candidate.getMatchList()) {
            if (allergyRejected) {
                // 过敏拒绝时只下发过敏标签，任何正面或灰色附注都不得出现。
                if (match.isAllergy() && match.getState() == TagState.REJECT) {
                    tagList.add(new Tag(DishRecommendInput.TagType.ALLERGY, match.getTagText()));
                }
            } else if (match.getState() == TagState.REJECT) {
                tagList.add(new Tag(match.getTagType(), match.getTagText()));
            } else if (match.getState() == TagState.RECOMMEND) {
                supplementalTagList.add(new Tag(match.getTagType(), match.getTagText()));
            }
        }
        return new DishCard(candidate.getDish().getDishId(), candidate.getDish().getDishName(),
                tagList, supplementalTagList, Collections.<String>emptyList());
    }

    private String reason(String dishName, DishRecommendInput.Match match) {
        StringBuilder ingredientBuilder = new StringBuilder();
        for (String ingredient : match.getMatchedIngredientList()) {
            if (ingredientBuilder.length() > 0) {
                ingredientBuilder.append(INGREDIENT_SEPARATOR);
            }
            ingredientBuilder.append(ingredient);
        }
        return dishName + "——含" + ingredientBuilder + "；报告原文：「" + match.getRawText() + "」";
    }

    private List<DishCard> truncate(List<DishCard> sourceList) {
        int resultSize = Math.min(MAX_DISPLAY_DISHES, sourceList.size());
        return Collections.unmodifiableList(new ArrayList<DishCard>(sourceList.subList(0,
                resultSize)));
    }

    /** 模块四完整输出。 */
    @Getter
    public static final class Result {
        private final List<DishCard> recommendList;
        private final List<DishCard> rejectList;
        private final String emptyState;
        private final String disclaimer;

        private Result(List<DishCard> recommendList, List<DishCard> rejectList,
                       String emptyState, String disclaimer) {
            this.recommendList = recommendList;
            this.rejectList = rejectList;
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 一道菜的展示 DTO。 */
    @Getter
    public static final class DishCard implements DisplayOrder.DishNameItem {
        private final long dishId;
        private final String dishName;
        private final List<Tag> tagList;
        private final List<Tag> supplementalTagList;
        private final List<String> reasonList;

        private DishCard(long dishId, String dishName, List<Tag> tagList,
                         List<Tag> supplementalTagList, List<String> reasonList) {
            this.dishId = dishId;
            this.dishName = dishName;
            this.tagList = Collections.unmodifiableList(new ArrayList<Tag>(tagList));
            this.supplementalTagList = Collections.unmodifiableList(
                    new ArrayList<Tag>(supplementalTagList));
            this.reasonList = Collections.unmodifiableList(new ArrayList<String>(reasonList));
        }
    }

    /** 一个展示标签。 */
    @Getter
    public static final class Tag {
        private final DishRecommendInput.TagType type;
        private final String text;

        private Tag(DishRecommendInput.TagType type, String text) {
            this.type = type;
            this.text = text;
        }
    }
}
