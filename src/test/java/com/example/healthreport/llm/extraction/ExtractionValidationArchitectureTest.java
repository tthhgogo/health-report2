package com.example.healthreport.llm.extraction;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R1：已经下线的三张医疗语义词表不得重新进入生产代码。 */
class ExtractionValidationArchitectureTest {

    /** 已下线的医疗语义词表类名；它们只记计数不影响输出，留在生产链路里没有意义。 */
    private static final Set<String> FORBIDDEN_TYPE_NAME_SET = new HashSet<String>(Arrays.asList(
            "ConclusionLabelWords", "NormalStatementWords", "AllergenSectionWords"));

    @Test
    void productionCodeShouldNotReferenceRetiredSemanticWordLists() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.healthreport");

        retiredSemanticWordListRule().check(productionClasses);
    }

    @Test
    void retiredSemanticWordListRuleShouldRejectAnIntentionalReference() {
        JavaClasses fixtureClasses = new ClassFileImporter().importClasses(
                ForbiddenSemanticReferenceFixture.class, ConclusionLabelWords.class);

        assertThatThrownBy(() -> retiredSemanticWordListRule().check(fixtureClasses))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ConclusionLabelWords");
    }

    @Test
    void extractionOutcomeShouldNotExposeIdentityFields() {
        // 汇总结果不得携带身份字段：patients 在同一性校验后即被剥离（withoutPatients）。
        assertThatThrownBy(() -> ExtractionOutcome.class.getMethod("getPatients"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    /** 创建同时适用于生产代码与负例夹具的 R1 依赖规则。 */
    private ArchRule retiredSemanticWordListRule() {
        return classes().should(new ArchCondition<JavaClass>("不依赖已下线的医疗语义词表") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    String targetName = dependency.getTargetClass().getSimpleName();
                    if (FORBIDDEN_TYPE_NAME_SET.contains(targetName)) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                item.getName() + " 引用了已下线词表 " + targetName));
                    }
                }
            }
        });
    }

    /** 仅用于证明 R1 规则会拦截真实字节码依赖。 */
    private static final class ForbiddenSemanticReferenceFixture {

        /** 故意保留的非法依赖，测试通过的前提是规则能够拒绝它。 */
        private final ConclusionLabelWords forbiddenReference = new ConclusionLabelWords();
    }

    /** 模拟已下线类型；只存在于测试夹具，不提供任何生产能力。 */
    private static final class ConclusionLabelWords {
    }
}
