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

/**
 * 生产计数下线断言：<b>生产环境不得存在任何进程级计数</b>。
 *
 * <p>原先这里维护着一份 13 个计数的「认可清单」。2026-08-27 全部下线，理由是它们
 * <b>只写不读</b>——每个计数都有自增点，却没有任何读取点，既不进日志也没有导出方式。
 * 一个读不到的计数不提供任何信息，只提供一处需要维护的并发状态；
 * 设计方案自己也写过「加一个计数器只是多一处状态」。</p>
 *
 * <p>本类因此从「清单必须一致」翻转为「一个都不许有」。将来若确有观测需求，
 * <b>先定清楚导出口径再实现</b>——先加计数、指望以后补导出，就是这次被删掉的那 13 个的来路。</p>
 */
class ProductionCounterContractTest {

    /**
     * 曾经存在过、现已全部下线的计数名，禁止以任何形式重新出现。
     *
     * <p>前三个是 2026-08-25 随语义词表一起移入离线评测的；
     * 其余 13 个是 2026-08-27 因只写不读整体下线的。</p>
     */
    private static final Set<String> RETIRED_COUNTER_NAME_SET = new HashSet<String>(Arrays.asList(
            "statusConflictCount", "foodBorneConflictCount", "normalAdmitSuspectCount",
            "schemaMissCount", "evidenceMissCount", "ocrFuzzyMatchCount",
            "allergenSuspectMissCount", "allergenPositiveUncoveredCount",
            "allergenUnknownCount", "adviceOtherCount", "sectionRefMissCount",
            "sectionUnknownCount", "highRiskSuppressedCount", "glyphLevelPdfCount",
            "residualNonStandardCount", "statusJudgedByModelCount"));

    @Test
    void productionMustNotDeclareAnyProcessLevelCounter() {
        Set<String> actualCounterNameSet = new HashSet<String>();
        for (JavaClass productionClass : productionClasses()) {
            for (JavaField field : productionClass.getFields()) {
                if (field.getRawType().isAssignableTo(AtomicLong.class)) {
                    actualCounterNameSet.add(productionClass.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(actualCounterNameSet)
                .as("生产环境不得存在进程级计数；确有观测需求请先定清楚导出口径")
                .isEmpty();
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

    /** 导入生产类且明确排除测试夹具，防止测试自身污染判定结果。 */
    private JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.healthreport");
    }
}
