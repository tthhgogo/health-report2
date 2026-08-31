package com.example.healthreport.dish;

import com.example.healthreport.llm.dishtag.DishTagInput;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** R17、R18b、R18c、R23b 的轻量架构契约。 */
class DishRecommendationArchitectureTest {

	@Test
	void everyModelInputFieldShouldBelongToOneHashCoverageCategory() {
		Set<String> directHashFieldSet = new HashSet<String>(
				Arrays.asList("dishList", "dishList[].dishName", "dishList[].ingredientList",
						"dishList[].ingredientList[].name", "dishList[].ingredientList[].weightG"));
		Set<String> versionCoveredFieldSet = new HashSet<String>(Arrays.asList("enumDisplayName", "avoidFoodList",
				"hiddenFoodList", "avoidDishPatternList", "cookingTipList"));
		Set<String> identifierFieldSet = new HashSet<String>(
				Arrays.asList("enumKey", "dishList[].companyId", "dishList[].dishId"));
		Set<String> classifiedFieldSet = new HashSet<String>();
		classifiedFieldSet.addAll(directHashFieldSet);
		classifiedFieldSet.addAll(versionCoveredFieldSet);
		classifiedFieldSet.addAll(identifierFieldSet);

		Set<String> actualFieldSet = declaredFieldNames(DishTagInput.class, "");
		actualFieldSet.addAll(declaredFieldNames(Dish.class, "dishList[]."));
		actualFieldSet.addAll(declaredFieldNames(DishIngredient.class, "dishList[].ingredientList[]."));
		assertThat(actualFieldSet).isEqualTo(classifiedFieldSet);
	}

	private Set<String> declaredFieldNames(Class<?> type, String prefix) {
		Set<String> fieldNameSet = new HashSet<String>();
		for (Field field : type.getDeclaredFields()) {
			if (!field.isSynthetic()) {
				fieldNameSet.add(prefix + field.getName());
			}
		}
		return fieldNameSet;
	}

	@Test
	void dishPackageShouldDeclareExactlyOneXxlJobAcrossAllTypes() {
		JavaClasses dishClassSet = importDishClasses();
		List<JavaMethod> handlerMethodList = new ArrayList<JavaMethod>();
		for (JavaClass dishClass : dishClassSet) {
			for (JavaMethod method : dishClass.getMethods()) {
				if (method.isAnnotatedWith(XxlJob.class)) {
					handlerMethodList.add(method);
				}
			}
		}

		assertThat(handlerMethodList).hasSize(1);
		JavaMethod handlerMethod = handlerMethodList.get(0);
		assertThat(handlerMethod.getOwner().getName()).isEqualTo(DishTagJob.class.getName());
		assertThat(handlerMethod.getName()).isEqualTo("execute");
	}

	@Test
	void dishPackageShouldNeverReadCurrentDateOutsideJobEntry() throws Exception {
		JavaClasses dishClassSet = importDishClasses();
		List<JavaMethodCall> currentDateCallList = new ArrayList<JavaMethodCall>();
		for (JavaClass dishClass : dishClassSet) {
			for (JavaMethod method : dishClass.getMethods()) {
				for (JavaMethodCall methodCall : method.getMethodCallsFromSelf()) {
					if (LocalDate.class.getName().equals(methodCall.getTargetOwner().getName())
							&& "now".equals(methodCall.getTarget().getName())) {
						currentDateCallList.add(methodCall);
					}
				}
			}
		}

		assertThat(currentDateCallList).hasSize(1);
		JavaMethodCall currentDateCall = currentDateCallList.get(0);
		assertThat(currentDateCall.getOriginOwner().getName()).isEqualTo(DishTagJob.class.getName());
		assertThat(currentDateCall.getOrigin().getName()).isEqualTo("execute");

		String dishSource = readDishSource();
		assertThat(countOccurrences(dishSource, "LocalDate.now()")).isEqualTo(1);
		String downstreamSource = dishSource.replace("LocalDate.now()", "");
		assertThat(downstreamSource).doesNotContain("now()", "CURRENT_DATE", "CURRENT_TIMESTAMP");
	}

	@Test
	void recommendationSourceShouldNotInventSeasoningClassificationOrAbsenceNeutralRule() throws Exception {
		String source = readRecommendationSource();

		assertThat(source).doesNotContain("SEASONING")
			.doesNotContain("食材表没有 X 所以判 NEUTRAL")
			.doesNotContain("missingAsNeutral");
	}

	@Test
	void dishTagDimensionsShouldBeBuiltOncePerPage() throws Exception {
		String dishTagServiceSource = new String(Files.readAllBytes(
				Paths.get("src/main/java/com/example/healthreport/dish/DishTagService.java")), StandardCharsets.UTF_8);

		// 一次是方法声明，一次是 processPage 调用；发布方向不得逐菜重新构造维度。
		assertThat(countOccurrences(dishTagServiceSource, "dimensions()")).isEqualTo(2);
	}

	/** 导入生产 classpath 中的整个 dish 包，避免新增类型逃过架构断言。 */
	private JavaClasses importDishClasses() {
		return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.example.healthreport.dish");
	}

	/** 读取 dish 包全部生产源码，供数据库当前日期表达式的静态断言使用。 */
	private String readDishSource() throws Exception {
		Path dishSourceRoot = Paths.get("src/main/java/com/example/healthreport/dish");
		return readSourceTree(dishSourceRoot, path -> true);
	}

	/** 读取菜品推荐链路源码，保护不引入调味料分类或缺失即中立规则。 */
	private String readRecommendationSource() throws Exception {
		Path sourceRoot = Paths.get("src/main/java/com/example/healthreport");
		return readSourceTree(sourceRoot,
				path -> path.toString().contains("/dish/") || path.toString().contains("/assemble/dishrecommend/")
						|| path.toString().contains("/safety/AllergenKeywordFallback"));
	}

	/** 遍历给定源码树并按条件拼接 Java 源码，读取失败时保持 fail-fast。 */
	private String readSourceTree(Path sourceRoot, java.util.function.Predicate<Path> predicate) throws Exception {
		StringBuilder sourceBuilder = new StringBuilder();
		try (Stream<Path> pathStream = Files.walk(sourceRoot)) {
			pathStream.filter(path -> path.toString().endsWith(".java"))
				.filter(predicate)
				.forEach(path -> appendSource(sourceBuilder, path));
		}
		return sourceBuilder.toString();
	}

	/** 统计源码中的固定字面量次数，确保入口日期调用既不缺失也不重复。 */
	private int countOccurrences(String source, String expectedText) {
		int occurrenceCount = 0;
		int searchFromIndex = 0;
		while ((searchFromIndex = source.indexOf(expectedText, searchFromIndex)) >= 0) {
			occurrenceCount++;
			searchFromIndex += expectedText.length();
		}
		return occurrenceCount;
	}

	private static void appendSource(StringBuilder sourceBuilder, Path path) {
		try {
			sourceBuilder.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
		}
		catch (java.io.IOException exception) {
			throw new IllegalStateException("源码契约读取失败", exception);
		}
	}

}
