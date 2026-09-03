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
    void analysisExecutorShouldBeTheOnlyFixedBoundedPool() {
        AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
        properties.setModelConcurrencyQuota(16);
        properties.setInstanceCount(1);
        properties.setHeapBudgetMb(2048);
        properties.setWebReservedMb(512);
        properties.setTaskPeakMb(256);
        properties.setQueueCapacity(3);
        ExecutorConfig config = new ExecutorConfig();

        ThreadPoolExecutor analysisExecutor = config.analysisExecutor(properties);
        try {
            // W = min(配额 16 ÷ 实例 1, 内存 (2048-512)/256 = 6) = 6：单任务只占 1 个在途配额。
            assertThat(analysisExecutor.getCorePoolSize()).isEqualTo(6);
            assertThat(analysisExecutor.getMaximumPoolSize()).isEqualTo(6);
            assertThat(analysisExecutor.getQueue().remainingCapacity()).isEqualTo(3);
            assertThat(analysisExecutor.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            analysisExecutor.shutdownNow();
        }
        // 批次池已随三阶段串行契约删除（P0-33e）：不存在第二个业务线程池 Bean。
        assertThat(ExecutorConfig.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("llmBatchExecutor"));
    }
}
