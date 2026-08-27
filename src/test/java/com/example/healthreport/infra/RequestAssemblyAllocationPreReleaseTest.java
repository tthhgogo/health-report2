package com.example.healthreport.infra;

import com.example.healthreport.llm.extraction.BatchPage;
import com.example.healthreport.llm.extraction.ExtractionBatchInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** R65p：固定八页样本的请求组装线程分配量基线。 */
@Slf4j
@Tag("release-gate")
@Tag("pre-release-only")
class RequestAssemblyAllocationPreReleaseTest {

    /** 每页固定 800 KiB，取自八页批次的分配量实测样本。 */
    private static final int PAGE_IMAGE_BYTES = 800 * 1024;

    /** 基线增加超过 30% 只告警，不作为失败条件。 */
    private static final double WARNING_RATIO = 1.30D;

    @Test
    void shouldMeasureEightPageRequestAssemblyAllocationWithoutHardFailure() throws Exception {
        com.sun.management.ThreadMXBean threadBean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertThat(threadBean.isThreadAllocatedMemorySupported()).isTrue();
        if (!threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }

        ExtractionProperties properties = new ExtractionProperties();
        properties.setBaseUrl("http://127.0.0.1");
        properties.setModel("allocation-probe");
        properties.setApiKey("allocation-probe");
        OpenAiCompatibleExtractionModelClient client =
                new OpenAiCompatibleExtractionModelClient(new ObjectMapper(), properties);
        ExtractionBatchInput input = fixedInput();
        long threadId = Thread.currentThread().getId();
        long beforeBytes = threadBean.getThreadAllocatedBytes(threadId);

        byte[] requestBytes = client.buildRequestBody(input);

        long allocatedBytes = threadBean.getThreadAllocatedBytes(threadId) - beforeBytes;
        assertThat(allocatedBytes).isPositive();
        assertThat(requestBytes.length).isLessThan(properties.getMaxRequestBodyBytes());
        writeCandidate(allocatedBytes, requestBytes.length);
        compareWithArchivedBaseline(allocatedBytes);
    }

    /** 构造固定八页、每页同尺寸的无业务内容样本。 */
    private ExtractionBatchInput fixedInput() {
        List<BatchPage> pageList = new ArrayList<BatchPage>(8);
        for (int page = 1; page <= 8; page++) {
            pageList.add(new BatchPage(page, "page=" + page,
                    new byte[PAGE_IMAGE_BYTES], true));
        }
        return new ExtractionBatchInput("allocation-probe", "extraction-2.3.0", 0, 0, 1, pageList);
    }

    /** 将本次候选值写入 target，供人工审核后归档，不修改仓库基线。 */
    private void writeCandidate(long allocatedBytes, int requestBytes) throws Exception {
        Path candidatePath = Paths.get("target/pre-release/r65p-candidate.properties");
        Files.createDirectories(candidatePath.getParent());
        String content = "allocatedBytes=" + allocatedBytes + "\nrequestBytes=" + requestBytes + "\n";
        Files.write(candidatePath, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("R65p 分配量测量完成，allocatedBytes={}，requestBytes={}",
                allocatedBytes, requestBytes);
    }

    /** 与上次归档比对；超出 30% 只告警，符合方案对环境噪音的处理。 */
    private void compareWithArchivedBaseline(long allocatedBytes) throws Exception {
        Path baselinePath = Paths.get("pre-release-results/r65p-baseline.properties");
        if (!Files.isRegularFile(baselinePath)) {
            log.warn("R65p 尚无归档基线，本次结果只能作为待审核候选");
            return;
        }
        Properties baseline = new Properties();
        try (java.io.InputStream inputStream = Files.newInputStream(baselinePath)) {
            baseline.load(inputStream);
        }
        long baselineBytes = Long.parseLong(baseline.getProperty("allocatedBytes"));
        assertThat(baselineBytes).isPositive();
        if ((double) allocatedBytes > (double) baselineBytes * WARNING_RATIO) {
            log.warn("R65p 分配量超过归档基线 30%，baselineBytes={}，allocatedBytes={}",
                    baselineBytes, allocatedBytes);
        }
    }
}
