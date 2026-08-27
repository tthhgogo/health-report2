package com.example.healthreport.llm.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.infra.ExtractionModelClient;
import com.example.healthreport.infra.LlmCallException;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.PartialReason;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R44/R45：四并发、乱序稳定合并、失败不取消和批次三态裁决。 */
class ExtractionBatchExecutorTest {

    private static final String RENDERED_TEXT_MARKER = "RENDERED_TEXT_MARKER";

    private Logger executorLogger;
    private Level previousLogLevel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void captureExecutorLogs() {
        executorLogger = (Logger) LoggerFactory.getLogger(ExtractionBatchExecutor.class);
        previousLogLevel = executorLogger.getLevel();
        logAppender = new ListAppender<ILoggingEvent>();
        logAppender.start();
        executorLogger.addAppender(logAppender);
        executorLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void stopCapturingExecutorLogs() {
        executorLogger.detachAppender(logAppender);
        executorLogger.setLevel(previousLogLevel);
        logAppender.stop();
    }

    @Test
    void shouldLimitOneTaskToFourConcurrentCallsAndSortOutOfOrderResults() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        ExtractionModelClient modelClient = input -> {
            int current = active.incrementAndGet();
            updateMaximum(maximum, current);
            try {
                Thread.sleep((8 - input.getBatchIndex()) * 10L);
                return "{\"batchStatus\":\"OK\"}";
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
            } finally {
                active.decrementAndGet();
            }
        };
        ThreadPoolExecutor executor = executor();
        try {
            List<ExtractionBatchResult> resultList = new ExtractionBatchExecutor(
                    executor, modelClient, new ObjectMapper()).execute(plans(8), new DegradeAccumulator());

            assertThat(maximum.get()).isLessThanOrEqualTo(4);
            assertThat(resultList).extracting(result -> result.getPlan().getInput().getBatchIndex())
                    .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneFailureShouldRunEveryOtherBatchWithoutRetryOrCancellation() {
        AtomicInteger callCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        ExtractionModelClient modelClient = input -> {
            callCount.incrementAndGet();
            if (input.getBatchIndex() == 0) {
                throw new LlmCallException(FailCode.SERVER_ERROR, 500, 1L);
            }
            try {
                Thread.sleep(15L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
            }
            successCount.incrementAndGet();
            return "{\"batchStatus\":\"OK\"}";
        };
        ThreadPoolExecutor executor = executor();
        try {
            assertThatThrownBy(() -> new ExtractionBatchExecutor(executor, modelClient, new ObjectMapper())
                    .execute(plans(8), new DegradeAccumulator()))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
            assertThat(callCount.get()).isEqualTo(8);
            assertThat(successCount.get()).isEqualTo(7);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oneUnreadableBatchShouldBeDiscardedAndDegradeRemainingResult() {
        ExtractionModelClient modelClient = input -> input.getBatchIndex() == 1
                ? "{\"batchStatus\":\"UNREADABLE\"}"
                : "{\"batchStatus\":\"OK\"}";
        DegradeAccumulator accumulator = new DegradeAccumulator();
        ThreadPoolExecutor executor = executor();
        try {
            List<ExtractionBatchResult> resultList = new ExtractionBatchExecutor(
                    executor, modelClient, new ObjectMapper()).execute(plans(3), accumulator);

            assertThat(resultList).extracting(result -> result.getPlan().getInput().getBatchIndex())
                    .containsExactly(0, 2);
            assertThat(accumulator.primaryReason()).isEqualTo(PartialReason.BATCH_UNREADABLE);
            assertThat(accumulator.suppressDietAdvice()).isTrue();
            assertThat(accumulator.suppressDishRecommend()).isTrue();
            assertThat(logAppender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("batchStatus=UNREADABLE", "partialReason=BATCH_UNREADABLE"))
                    .allSatisfy(message -> assertThat(message).doesNotContain(RENDERED_TEXT_MARKER));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allNoReportAndAllUnreadableMustRemainDifferentFailures() {
        assertAllStatusFailure("NO_REPORT_FEATURE", FailCode.NOT_HEALTH_REPORT);
        assertAllStatusFailure("UNREADABLE", FailCode.UNREADABLE);
        assertThat(logAppender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains("batchStatus=NO_REPORT_FEATURE", "failCode=NOT_HEALTH_REPORT"))
                .anySatisfy(message -> assertThat(message)
                        .contains("batchStatus=UNREADABLE", "failCode=UNREADABLE"));
    }

    @Test
    void unsafePageAssemblyFailureShouldLogSafeTypeAndFailTask() {
        ThreadPoolExecutor executor = executor();
        try {
            assertThatThrownBy(() -> new ExtractionBatchExecutor(executor,
                    input -> {
                        throw new IllegalStateException("必需的页面图缺失");
                    }, new ObjectMapper()).execute(plans(1), new DegradeAccumulator()))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
            assertThat(logAppender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("batchIndex=0", "batchStatus=FAILED",
                                "异常类型=java.lang.IllegalStateException")
                        .doesNotContain(RENDERED_TEXT_MARKER);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName())
                        .isEqualTo(IllegalStateException.class.getName());
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void malformedBatchStatusShouldBeSanitizedToServerError() {
        ThreadPoolExecutor executor = executor();
        try {
            assertThatThrownBy(() -> new ExtractionBatchExecutor(executor,
                    input -> "{not-json-sensitive-fragment", new ObjectMapper())
                    .execute(plans(1), new DegradeAccumulator()))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
            assertThat(logAppender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("not-json-sensitive-fragment"));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertAllStatusFailure(String status, FailCode expectedFailCode) {
        ThreadPoolExecutor executor = executor();
        try {
            assertThatThrownBy(() -> new ExtractionBatchExecutor(executor,
                    input -> "{\"batchStatus\":\"" + status + "\"}", new ObjectMapper())
                    .execute(plans(3), new DegradeAccumulator()))
                    .isInstanceOfSatisfying(HealthReportException.class,
                            exception -> assertThat(exception.getFailCode()).isEqualTo(expectedFailCode));
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ExtractionBatchPlan> plans(int count) {
        List<ExtractionBatchPlan> planList = new ArrayList<ExtractionBatchPlan>(count);
        for (int index = 0; index < count; index++) {
            int fileIndex = index < count / 2 ? 0 : 1;
            ParsedPage page = new ParsedPage(index + 1,
                    Collections.singletonList(new Segment(
                            Segment.id(fileIndex, index + 1, 0), "raw", RENDERED_TEXT_MARKER,
                            TextSource.NATIVE, null)), new byte[]{1}, true);
            BatchAddressing addressing = new BatchAddressing(fileIndex, Collections.singletonList(page));
            ExtractionBatchInput input = new ExtractionBatchInput("system", "extraction-2.3.0", fileIndex,
                    index, count, addressing.getPageList());
            planList.add(new ExtractionBatchPlan(input, addressing));
        }
        return planList;
    }

    private ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(16));
    }

    private void updateMaximum(AtomicInteger maximum, int current) {
        int previous;
        do {
            previous = maximum.get();
            if (current <= previous) {
                return;
            }
        } while (!maximum.compareAndSet(previous, current));
    }
}
