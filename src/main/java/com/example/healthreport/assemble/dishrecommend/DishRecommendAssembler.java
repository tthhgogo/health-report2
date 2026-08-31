package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.dish.DishDisposition;
import com.example.healthreport.dish.TagState;
import com.example.healthreport.dish.TagStateResolver;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

	private final TagStateResolver tagStateResolver;

	private final DisplayOrder displayOrder;

	public DishRecommendAssembler(TagStateResolver tagStateResolver, DisplayOrder displayOrder) {
		this.tagStateResolver = tagStateResolver;
		this.displayOrder = displayOrder;
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
			DishDisposition disposition = tagStateResolver.resolve(facts(candidate.getMatchList()));
			if (disposition == DishDisposition.RECOMMENDED) {
				recommendCardList.add(recommendedCard(candidate));
			}
			else if (disposition == DishDisposition.NOT_RECOMMENDED) {
				rejectCardList.add(rejectedCard(candidate));
			}
		}
		recommendCardList = truncate(displayOrder.sortDishByPinyin(recommendCardList));
		rejectCardList = truncate(displayOrder.sortDishByPinyin(rejectCardList));

		String emptyState = null;
		if (!input.isFormalAdvicePresent()) {
			emptyState = EmptyStateConstants.MODULE_FOUR_NO_ADVICE;
		}
		else if (recommendCardList.isEmpty() && rejectCardList.isEmpty()) {
			emptyState = EmptyStateConstants.MODULE_FOUR_NO_MATCH;
		}
		return new Result(recommendCardList, rejectCardList, emptyState, DisclaimerConstants.MODULE_FOUR);
	}

	private List<TagStateResolver.Fact> facts(List<DishRecommendInput.Match> matchList) {
		List<TagStateResolver.Fact> factList = new ArrayList<TagStateResolver.Fact>(matchList.size());
		for (DishRecommendInput.Match match : matchList) {
			factList.add(new TagStateResolver.Fact(match.getState(), match.isRejectCapable(), match.isAllergy()));
		}
		return factList;
	}

	private RecommendedDishCard recommendedCard(DishRecommendInput.Candidate candidate) {
		Set<String> recommendTagSet = new LinkedHashSet<String>();
		Set<String> recommendReasonSet = new LinkedHashSet<String>();
		for (DishRecommendInput.Match match : candidate.getMatchList()) {
			if (match.getState() == TagState.RECOMMEND) {
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
			if (match.getState() == TagState.REJECT) {
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
	@Getter
	public static final class Result {

		private final List<RecommendedDishCard> recommendList;

		private final List<NotRecommendedDishCard> rejectList;

		private final String emptyState;

		private final String disclaimer;

		private Result(List<RecommendedDishCard> recommendList, List<NotRecommendedDishCard> rejectList,
				String emptyState, String disclaimer) {
			this.recommendList = recommendList;
			this.rejectList = rejectList;
			this.emptyState = emptyState;
			this.disclaimer = disclaimer;
		}

	}

	/** 推荐菜输出：只返回菜名、推荐标签与报告原文理由。 */
	@Getter
	public static final class RecommendedDishCard implements DisplayOrder.DishNameItem {

		@JsonIgnore
		private final long dishId;

		private final String dishName;

		private final List<String> recommendTagList;

		private final List<String> recommendReasonList;

		private RecommendedDishCard(long dishId, String dishName, List<String> recommendTagList,
				List<String> recommendReasonList) {
			this.dishId = dishId;
			this.dishName = dishName;
			this.recommendTagList = Collections.unmodifiableList(new ArrayList<String>(recommendTagList));
			this.recommendReasonList = Collections.unmodifiableList(new ArrayList<String>(recommendReasonList));
		}

	}

	/** 不推荐菜输出：只返回菜名与全部命中的负向标签。 */
	@Getter
	public static final class NotRecommendedDishCard implements DisplayOrder.DishNameItem {

		@JsonIgnore
		private final long dishId;

		private final String dishName;

		private final List<String> notRecommendTagList;

		private NotRecommendedDishCard(long dishId, String dishName, List<String> notRecommendTagList) {
			this.dishId = dishId;
			this.dishName = dishName;
			this.notRecommendTagList = Collections.unmodifiableList(new ArrayList<String>(notRecommendTagList));
		}

	}

}
