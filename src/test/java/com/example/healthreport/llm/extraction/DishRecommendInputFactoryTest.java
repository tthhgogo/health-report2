package com.example.healthreport.llm.extraction;

import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInput;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.cache.DishTagSetRef;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.dish.TagStateResolver;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.safety.StructuredAdmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模块四企业 Redis 集合并、差、标签恢复与原文理由回归。 */
class DishRecommendInputFactoryTest {

	/** 固定业务日，断言在线读取不自行获取系统日期。 */
	private static final LocalDate BIZ_DATE = LocalDate.of(2026, 8, 28);

	/** 固定企业，断言在线集合不跨企业读取。 */
	private static final String COMPANY_ID = "company-a";

	private DishRecommendSetCache setCache;

	private DishSetMemberCodec memberCodec;

	private DishRecommendInputFactory factory;

	private DishRecommendAssembler assembler;

	@BeforeEach
	void setUp() {
		setCache = mock(DishRecommendSetCache.class);
		memberCodec = new DishSetMemberCodec();
		StructuredAdmission admission = mock(StructuredAdmission.class);
		when(admission.shouldSuppress(any(), any(), any(), anyList())).thenReturn(false);
		factory = new DishRecommendInputFactory(setCache, memberCodec, admission);
		assembler = new DishRecommendAssembler(new TagStateResolver(), new DisplayOrder());
	}

	@Test
	void onlyAllergenShouldReturnRejectDishAndKeepRecommendEmpty() {
		ValidatedExtractionOutput output = output(true, false, false);
		String member = memberCodec.encode(1L, "鲜虾炒饭");
		when(setCache.read(eq(COMPANY_ID), eq(BIZ_DATE), anyList()))
			.thenAnswer(invocation -> singleSet(invocation.getArgument(2), DishTagSetRef.Category.ALLERGEN,
					DishTagSetRef.Direction.REJECT, "SHRIMP_CRAB", member));

		DishRecommendAssembler.Result result = assembler.assemble(factory.create(COMPANY_ID, BIZ_DATE, output, false));

		assertThat(result.getRecommendList()).isEmpty();
		assertThat(result.getRejectList()).hasSize(1);
		assertThat(result.getRejectList().get(0).getDishName()).isEqualTo("鲜虾炒饭");
		assertThat(result.getRejectList().get(0).getNotRecommendTagList()).containsExactly("虾蟹类过敏");
	}

	@Test
	void rejectSetShouldRemovePositiveAndSuppressAllPositiveFields() {
		ValidatedExtractionOutput output = output(true, true, false);
		String member = memberCodec.encode(2L, "虾仁猪肝汤");
		when(setCache.read(eq(COMPANY_ID), eq(BIZ_DATE), anyList()))
			.thenAnswer(invocation -> twoSets(invocation.getArgument(2), member));

		DishRecommendAssembler.Result result = assembler.assemble(factory.create(COMPANY_ID, BIZ_DATE, output, false));

		assertThat(result.getRecommendList()).isEmpty();
		assertThat(result.getRejectList()).hasSize(1);
		assertThat(result.getRejectList().get(0).getNotRecommendTagList()).containsExactly("虾蟹类过敏");
	}

	@Test
	void nutritionRecommendationShouldUseReportRawTextWithoutIngredientReason() {
		ValidatedExtractionOutput output = output(false, true, false);
		String member = memberCodec.encode(3L, "菠菜猪肝汤");
		when(setCache.read(eq(COMPANY_ID), eq(BIZ_DATE), anyList()))
			.thenAnswer(invocation -> singleSet(invocation.getArgument(2), DishTagSetRef.Category.NUTRITION,
					DishTagSetRef.Direction.RECOMMEND, "IRON", member));

		DishRecommendAssembler.Result result = assembler.assemble(factory.create(COMPANY_ID, BIZ_DATE, output, false));

		assertThat(result.getRecommendList()).hasSize(1);
		assertThat(result.getRecommendList().get(0).getRecommendTagList()).containsExactly("补铁");
		assertThat(result.getRecommendList().get(0).getRecommendReasonList()).containsExactly("建议补铁");
	}

	@Test
	void malformedRedisMemberShouldBeSkippedWithoutFailingModuleFour() {
		ValidatedExtractionOutput output = output(false, true, false);
		String validMember = memberCodec.encode(4L, "牛肉菠菜汤");
		when(setCache.read(eq(COMPANY_ID), eq(BIZ_DATE), anyList())).thenAnswer(invocation -> {
			List<DishTagSetRef> refList = invocation.getArgument(2);
			Map<DishTagSetRef, Set<String>> resultMap = emptyMap(refList);
			for (DishTagSetRef ref : refList) {
				if (ref.getCategory() == DishTagSetRef.Category.NUTRITION
						&& ref.getDirection() == DishTagSetRef.Direction.RECOMMEND
						&& "IRON".equals(ref.getEnumKey())) {
					Set<String> memberSet = new LinkedHashSet<String>();
					memberSet.add(validMember);
					memberSet.add("invalid-member");
					resultMap.put(ref, memberSet);
				}
			}
			return resultMap;
		});

		DishRecommendAssembler.Result result = assembler.assemble(factory.create(COMPANY_ID, BIZ_DATE, output, false));

		assertThat(result.getRecommendList()).hasSize(1);
		assertThat(result.getRecommendList().get(0).getDishName()).isEqualTo("牛肉菠菜汤");
		assertThat(result.getRejectList()).isEmpty();
	}

	@Test
	void suppressedModuleShouldNotReadRedis() {
		DishRecommendInput input = factory.create(COMPANY_ID, BIZ_DATE, output(false, true, false), true);

		assertThat(assembler.assemble(input)).isNull();
		verify(setCache, never()).read(any(String.class), any(LocalDate.class), anyList());
	}

	private Map<DishTagSetRef, Set<String>> singleSet(List<DishTagSetRef> refList, DishTagSetRef.Category category,
			DishTagSetRef.Direction direction, String enumKey, String member) {
		Map<DishTagSetRef, Set<String>> resultMap = emptyMap(refList);
		for (DishTagSetRef ref : refList) {
			if (ref.getCategory() == category && ref.getDirection() == direction && ref.getEnumKey().equals(enumKey)) {
				resultMap.put(ref, Collections.singleton(member));
			}
		}
		return resultMap;
	}

	private Map<DishTagSetRef, Set<String>> twoSets(List<DishTagSetRef> refList, String member) {
		Map<DishTagSetRef, Set<String>> resultMap = emptyMap(refList);
		for (DishTagSetRef ref : refList) {
			if (ref.getEnumKey().equals("SHRIMP_CRAB") || ref.getEnumKey().equals("IRON")) {
				resultMap.put(ref, Collections.singleton(member));
			}
		}
		return resultMap;
	}

	private Map<DishTagSetRef, Set<String>> emptyMap(List<DishTagSetRef> refList) {
		Map<DishTagSetRef, Set<String>> resultMap = new LinkedHashMap<DishTagSetRef, Set<String>>();
		for (DishTagSetRef ref : refList) {
			resultMap.put(ref, Collections.<String>emptySet());
		}
		return resultMap;
	}

	private ValidatedExtractionOutput output(boolean allergen, boolean nutrition, boolean diet) {
		Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
		List<ValidatedExtractionOutput.Allergen> allergenList = new ArrayList<ValidatedExtractionOutput.Allergen>();
		List<ValidatedExtractionOutput.AdviceItem<NutritionKey>> nutritionList = new ArrayList<ValidatedExtractionOutput.AdviceItem<NutritionKey>>();
		List<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>> dietList = new ArrayList<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>();
		if (allergen) {
			segmentByIdMap.put("f0-p1-s0", segment("f0-p1-s0", "虾蟹过敏阳性"));
			allergenList
				.add(new ValidatedExtractionOutput.Allergen(0, 0, 0, 0, 0, 1, Collections.singletonList("f0-p1-s0"),
						AllergenKey.SHRIMP_CRAB, true, "虾蟹", "阳性", AllergenResultStatus.POSITIVE));
		}
		if (nutrition) {
			segmentByIdMap.put("f0-p1-s1", segment("f0-p1-s1", "建议补铁"));
			nutritionList.add(new ValidatedExtractionOutput.AdviceItem<NutritionKey>(0, 0, 0, 0, null, 0, 1,
					Collections.singletonList("f0-p1-s1"), NutritionKey.IRON, "建议补铁",
					AdviceApplicability.CURRENT_PATIENT, AdviceStructuredSafety.NORMAL));
		}
		if (diet) {
			segmentByIdMap.put("f0-p1-s2", segment("f0-p1-s2", "建议低嘌呤"));
			dietList.add(new ValidatedExtractionOutput.AdviceItem<DietRequirementKey>(0, 0, 0, 0, null, 0, 1,
					Collections.singletonList("f0-p1-s2"), DietRequirementKey.LOW_PURINE, "建议低嘌呤",
					AdviceApplicability.CURRENT_PATIENT, AdviceStructuredSafety.NORMAL));
		}
		return new ValidatedExtractionOutput(Collections.<ValidatedExtractionOutput.ReportOverview>emptyList(),
				Collections.<ValidatedExtractionOutput.Section>emptyList(),
				Collections.<ValidatedExtractionOutput.Indicator>emptyList(),
				Collections.<ValidatedExtractionOutput.TextualFinding>emptyList(),
				Collections.<ValidatedExtractionOutput.SummaryConclusion>emptyList(), allergenList, nutritionList,
				dietList, Collections.<String>emptySet(), Collections.<String>emptySet(), segmentByIdMap);
	}

	private Segment segment(String id, String text) {
		return new Segment(id, text, text, TextSource.OCR, null);
	}

}
