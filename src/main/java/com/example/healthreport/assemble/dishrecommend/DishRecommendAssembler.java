package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 模块四集合裁决、标签净化、拼音排序与最终截断的唯一组装器。 */
@Service
public class DishRecommendAssembler {

	/** 推荐与不推荐列表各最多展示三道菜。 */
	private static final int MAX_DISPLAY_DISHES = 3;

	private final DishNameSorter dishNameSorter;

	public DishRecommendAssembler(DishNameSorter dishNameSorter) {
		this.dishNameSorter = dishNameSorter;
	}

	/** 先对全部候选完成拒绝优先裁决，再分别排序并截断。 */
	public Result assemble(DishRecommendInput input) {
		if (input == null) {
			throw new IllegalArgumentException("模块四输入不能为空");
		}
		if (input.isSuppressDishRecommend()) {
			return null;
		}
		List<RecommendedDishCard> recommendCardList = new ArrayList<RecommendedDishCard>();
		List<NotRecommendedDishCard> rejectCardList = new ArrayList<NotRecommendedDishCard>();
		for (DishRecommendInput.Candidate candidate : input.getCandidateList()) {
			// 拒绝优先：任一方向命中拒绝即进不推荐列表，正向命中不可恢复（需求 §8-3）。
			if (hasReject(candidate)) {
				rejectCardList.add(rejectedCard(candidate));
			}
			else if (!candidate.getMatchList().isEmpty()) {
				recommendCardList.add(recommendedCard(candidate));
			}
		}
		recommendCardList = truncate(dishNameSorter.sortByPinyin(recommendCardList));
		rejectCardList = truncate(dishNameSorter.sortByPinyin(rejectCardList));

		String emptyState = null;
		if (!input.isFormalAdvicePresent()) {
			emptyState = EmptyStateConstants.MODULE_FOUR_NO_ADVICE;
		}
		else if (recommendCardList.isEmpty() && rejectCardList.isEmpty()) {
			emptyState = EmptyStateConstants.MODULE_FOUR_NO_MATCH;
		}
		return new Result(recommendCardList, rejectCardList, emptyState, DisclaimerConstants.MODULE_FOUR);
	}

	private boolean hasReject(DishRecommendInput.Candidate candidate) {
		for (DishRecommendInput.Match match : candidate.getMatchList()) {
			if (match.isReject()) {
				return true;
			}
		}
		return false;
	}

	private RecommendedDishCard recommendedCard(DishRecommendInput.Candidate candidate) {
		Set<String> recommendTagSet = new LinkedHashSet<String>();
		Set<String> recommendReasonSet = new LinkedHashSet<String>();
		for (DishRecommendInput.Match match : candidate.getMatchList()) {
			if (!match.isReject()) {
				recommendTagSet.add(match.getTagText());
				recommendReasonSet.add(match.getRawText());
			}
		}
		return new RecommendedDishCard(candidate.getDishId(), candidate.getDishName(),
				new ArrayList<String>(recommendTagSet), new ArrayList<String>(recommendReasonSet));
	}

	private NotRecommendedDishCard rejectedCard(DishRecommendInput.Candidate candidate) {
		Set<String> rejectTagSet = new LinkedHashSet<String>();
		for (DishRecommendInput.Match match : candidate.getMatchList()) {
			if (match.isReject()) {
				rejectTagSet.add(match.getTagText());
			}
		}
		// 任何拒绝方向都只返回负向标签；正向标签和理由不会进入本 DTO。
		return new NotRecommendedDishCard(candidate.getDishId(), candidate.getDishName(),
				new ArrayList<String>(rejectTagSet));
	}

	private <T> List<T> truncate(List<T> sourceList) {
		int resultSize = Math.min(MAX_DISPLAY_DISHES, sourceList.size());
		return Collections.unmodifiableList(new ArrayList<T>(sourceList.subList(0, resultSize)));
	}

	/** 模块四完整输出。 */
	@ApiModel(value = "DishRecommendResult", description = "模块四（食堂菜品推荐）完整返回结构")
	@Getter
	public static final class Result {

		@ApiModelProperty(value = "推荐菜品列表，最多 3 道，拼音排序", required = true)
		private final List<RecommendedDishCard> recommendList;

		@ApiModelProperty(value = "不推荐菜品列表，最多 3 道，拼音排序", required = true)
		private final List<NotRecommendedDishCard> rejectList;

		@ApiModelProperty(value = "两个列表均为空时的空态文案；有内容时为 null",
				example = "本次未匹配到符合建议的食堂菜品，菜品以食堂实际上架为准。")
		private final String emptyState;

		@ApiModelProperty(value = "模块底部声明", required = true)
		private final String disclaimer;

		@JsonCreator
		private Result(@JsonProperty("recommendList") List<RecommendedDishCard> recommendList,
				@JsonProperty("rejectList") List<NotRecommendedDishCard> rejectList,
				@JsonProperty("emptyState") String emptyState,
				@JsonProperty("disclaimer") String disclaimer) {
			this.recommendList = recommendList;
			this.rejectList = rejectList;
			this.emptyState = emptyState;
			this.disclaimer = disclaimer;
		}

	}

	/** 推荐菜输出：只返回菜名、推荐标签与报告原文理由。 */
	@ApiModel(value = "RecommendedDishCard", description = "推荐菜卡片；只含菜名、推荐标签与报告原文理由")
	@Getter
	public static final class RecommendedDishCard implements DishNameSorter.DishNameItem {

		@ApiModelProperty(value = "菜品 ID；内部字段，不序列化到响应", hidden = true)
		@JsonIgnore
		private final long dishId;

		@ApiModelProperty(value = "菜名", required = true, example = "清蒸鲈鱼")
		private final String dishName;

		@ApiModelProperty(value = "推荐标签文字列表", required = true, example = "[\"低嘌呤\"]")
		private final List<String> recommendTagList;

		@ApiModelProperty(value = "推荐理由，引用报告原文", required = true)
		private final List<String> recommendReasonList;

		private RecommendedDishCard(long dishId, String dishName, List<String> recommendTagList,
				List<String> recommendReasonList) {
			this.dishId = dishId;
			this.dishName = dishName;
			this.recommendTagList = Collections.unmodifiableList(new ArrayList<String>(recommendTagList));
			this.recommendReasonList = Collections.unmodifiableList(new ArrayList<String>(recommendReasonList));
		}

		/**
		 * dishId 被 {@code @JsonIgnore} 排除在序列化外，缓存读出只能回填占位 0：
		 * dishId 仅在组装期供拼音排序平局与集合匹配使用，读出后不再排序，占位安全。
		 */
		@JsonCreator
		RecommendedDishCard(@JsonProperty("dishName") String dishName,
				@JsonProperty("recommendTagList") List<String> recommendTagList,
				@JsonProperty("recommendReasonList") List<String> recommendReasonList) {
			this(0L, dishName, recommendTagList, recommendReasonList);
		}

	}

	/** 不推荐菜输出：只返回菜名与全部命中的负向标签。 */
	@ApiModel(value = "NotRecommendedDishCard", description = "不推荐菜卡片；只含菜名与全部命中的负向标签")
	@Getter
	public static final class NotRecommendedDishCard implements DishNameSorter.DishNameItem {

		@ApiModelProperty(value = "菜品 ID；内部字段，不序列化到响应", hidden = true)
		@JsonIgnore
		private final long dishId;

		@ApiModelProperty(value = "菜名", required = true, example = "麻辣小龙虾")
		private final String dishName;

		@ApiModelProperty(value = "命中的不推荐标签文字列表", required = true, example = "[\"虾蟹类过敏\"]")
		private final List<String> notRecommendTagList;

		private NotRecommendedDishCard(long dishId, String dishName, List<String> notRecommendTagList) {
			this.dishId = dishId;
			this.dishName = dishName;
			this.notRecommendTagList = Collections.unmodifiableList(new ArrayList<String>(notRecommendTagList));
		}

		/**
		 * dishId 被 {@code @JsonIgnore} 排除在序列化外，缓存读出只能回填占位 0：
		 * dishId 仅在组装期供拼音排序平局与集合匹配使用，读出后不再排序，占位安全。
		 */
		@JsonCreator
		NotRecommendedDishCard(@JsonProperty("dishName") String dishName,
				@JsonProperty("notRecommendTagList") List<String> notRecommendTagList) {
			this(0L, dishName, notRecommendTagList);
		}

	}

}
