package com.example.healthreport.task;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** 两池分离的结构和运行时配置断言。 */
class TaskExecutorArchitectureTest {

    @Test
    void workerMustNotReachTaskThreadPoolDirectly() {
        ArchRule rule = noClasses().that().haveSimpleName("AnalysisTaskWorker")
                .should().dependOnClassesThat().areAssignableTo(ThreadPoolExecutor.class);
        rule.check(new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.example.healthreport.task"));
    }

    @Test
    void analysisAndBatchExecutorsShouldBeDistinctFixedBoundedPools() {
        AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
        properties.setModelConcurrencyQuota(16);
        properties.setInstanceCount(1);
        properties.setHeapBudgetMb(2048);
        properties.setWebReservedMb(512);
        properties.setTaskPeakMb(256);
        properties.setQueueCapacity(3);
        ExecutorConfig config = new ExecutorConfig();

        ThreadPoolExecutor analysisExecutor = config.analysisExecutor(properties);
        ThreadPoolExecutor batchExecutor = config.llmBatchExecutor(properties);
        try {
            assertThat(analysisExecutor).isNotSameAs(batchExecutor);
            assertThat(analysisExecutor.getCorePoolSize()).isEqualTo(4);
            assertThat(analysisExecutor.getMaximumPoolSize()).isEqualTo(4);
            assertThat(analysisExecutor.getQueue().remainingCapacity()).isEqualTo(3);
            assertThat(analysisExecutor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(batchExecutor.getCorePoolSize()).isEqualTo(16);
        } finally {
            analysisExecutor.shutdownNow();
            batchExecutor.shutdownNow();
        }
    }
}
