package com.example.healthreport.safety;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.constants.Bucket;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** R14 与枚举外过敏原食源性边界。 */
class AllergenKeywordFallbackTest {

	private final AllergenKeywordFallback fallback = new AllergenKeywordFallback();

	@Test
	void everyGroupShouldPartitionAllWordsWithoutOverlap() {
		for (AllergenGroup group : AllergenGroups.ALL.values()) {
			Set<String> avoidSet = new HashSet<String>();
			Set<String> hiddenSet = new HashSet<String>();
			Set<String> allSet = new HashSet<String>();
			for (AllergenWord word : group.getWordList()) {
				allSet.add(word.getMatchWord());
				if (word.getBucket() == Bucket.AVOID) {
					avoidSet.add(word.getMatchWord());
				}
				else {
					hiddenSet.add(word.getMatchWord());
				}
			}
			assertThat(Collections.disjoint(avoidSet, hiddenSet)).isTrue();
			Set<String> unionSet = new HashSet<String>(avoidSet);
			unionSet.addAll(hiddenSet);
			assertThat(unionSet).isEqualTo(allSet);
		}
	}

	@Test
	void nonFoodOtherShouldNeverEnterDishMatching() {
		Dish dish = new Dish(1L, "艾草青团", Collections.singletonList(new DishIngredient("艾草", null)));

		assertThat(fallback.matchesOther("艾草", false, dish)).isFalse();
		assertThat(fallback.matchesOther("艾草", true, dish)).isTrue();
	}

	@Test
	void reviewedFormalWordsShouldParticipateInHardFallback() {
		Dish dish = new Dish(2L, "花生拌菜", Collections.singletonList(new DishIngredient("花生", null)));

		assertThat(fallback.matches(AllergenGroups.PEANUT, dish)).isTrue();
	}

}
