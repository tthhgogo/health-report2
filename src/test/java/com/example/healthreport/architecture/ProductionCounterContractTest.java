package com.example.healthreport.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 生产计数清单断言：清单之外不得出现任何进程级计数字段。 */
class ProductionCounterContractTest {

    /** 唯一允许存在的进程级生产计数字段；新增计数必须先登记到这里。 */
    private static final Set<String> ALLOWED_COUNTER_NAME_SET = new HashSet<String>(Arrays.asList(
            "schemaMissCount", "evidenceMissCount", "ocrFuzzyMatchCount",
            "allergenSuspectMissCount", "allergenPositiveUncoveredCount",
            "allergenUnknownCount", "adviceOtherCount", "sectionRefMissCount",
            "sectionUnknownCount", "highRiskSuppressedCount", "glyphLevelPdfCount",
            "residualNonStandardCount", "statusJudgedByModelCount"));

    /** 已从设计中删除且禁止重新实现的历史计数。 */
    private static final Set<String> RETIRED_COUNTER_NAME_SET = new HashSet<String>(Arrays.asList(
            "statusConflictCount", "foodBorneConflictCount", "normalAdmitSuspectCount"));

    @Test
    void atomicLongProductionCountersShouldExactlyMatchTheApprovedList() {
        JavaClasses productionClasses = productionClasses();
        Set<String> actualCounterNameSet = new HashSet<String>();
        for (JavaClass productionClass : productionClasses) {
            for (JavaField field : productionClass.getFields()) {
                if (field.getRawType().isAssignableTo(AtomicLong.class)) {
                    actualCounterNameSet.add(field.getName());
                }
            }
        }

        assertThat(actualCounterNameSet).containsExactlyInAnyOrderElementsOf(
                ALLOWED_COUNTER_NAME_SET);
    }

    @Test
    void retiredCountersShouldNotExistAsProductionMembers() {
        JavaClasses productionClasses = productionClasses();
        Set<String> productionMemberNameSet = new HashSet<String>();
        for (JavaClass productionClass : productionClasses) {
            for (JavaField field : productionClass.getFields()) {
                productionMemberNameSet.add(field.getName());
            }
            productionClass.getMethods().forEach(method -> productionMemberNameSet.add(method.getName()));
        }

        assertThat(productionMemberNameSet).doesNotContainAnyElementsOf(RETIRED_COUNTER_NAME_SET);
    }

    /** 导入生产类且明确排除测试夹具，防止测试自身污染计数清单。 */
    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.healthreport");
    }
}
