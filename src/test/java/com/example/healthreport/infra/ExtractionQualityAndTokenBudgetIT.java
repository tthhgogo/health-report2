package com.example.healthreport.infra;

import com.example.healthreport.llm.extraction.BatchPage;
import com.example.healthreport.llm.extraction.BatchPlanner;
import com.example.healthreport.llm.extraction.ExtractionBatchInput;
import com.example.healthreport.llm.extraction.ExtractionBatchPlan;
import com.example.healthreport.llm.extraction.ExtractionPromptProvider;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.example.healthreport.parse.CapacityPrecheckService;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.ExtractionImageCompressor;
import com.example.healthreport.parse.FileParseService;
import com.example.healthreport.parse.ImageContentInspector;
import com.example.healthreport.parse.OcrImageEncoder;
import com.example.healthreport.parse.ExifOrientationTransform;
import com.example.healthreport.parse.WordDocumentInspector;
import com.example.healthreport.parse.ZipBombGuard;
import com.example.healthreport.parse.ocr.OcrBboxNormalizer;
import com.example.healthreport.parse.ocr.OcrPageSegmentFactory;
import com.example.healthreport.parse.segment.GlyphDensityGate;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.word.WordCapacityGuard;
import com.example.healthreport.parse.ofd.OfdSegmentParser;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.pdf.PdfPageRenderer;
import com.example.healthreport.parse.pdf.PdfSegmentParser;
import com.example.healthreport.parse.PdfTextLayerChecker;
import com.example.healthreport.parse.RenderedPageImageProcessor;
import com.example.healthreport.parse.word.WordSegmentParser;
import com.example.healthreport.parse.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实体检报告量三个数：Schema 一次通过率、每批输入 token、页面图占多少 token。
 *
 * <p><b>这三个数决定三件事：</b>要不要上 {@code json_schema}（通过率够高就不值）、
 * 49k/批 的估算准不准（实测已证明它偏低约 2 倍，§4.1.5 的 60k 硬约束已据此删除）、
 * 以及超预算时该动哪个杠杆（图像长边 / 每批页数 / Schema 描述）。</p>
 *
 * <p><b>报告正文一个字都不出现在日志里。</b> 只记数量、token 数、
 * 以及 Schema 违规的关键字与 JSON 路径——路径形如 {@code $.indicators[3].status}，
 * 不含任何模型输出的值。样本文件走仓库外的绝对路径，<b>绝不放进 src/test/resources</b>：
 * 那是真实姓名与健康结论，提交进 git 就永久留在历史里（§2.7）。</p>
 *
 * <pre>
 * set HEALTH_REPORT_SAMPLE=D:\samples\report.pdf
 * set EXTRACTION_BASE_URL=http://.../public
 * set EXTRACTION_MODEL=Qwen3-VL-8B-Instruct-K100
 * set EXTRACTION_API_KEY=...
 * set HEALTH_REPORT_REPEAT=3
 * mvn test -Dtest=ExtractionQualityAndTokenBudgetIT
 * </pre>
 */
@Slf4j(topic = "EXTRACTION_QUALITY_AND_TOKEN_BUDGET")
class ExtractionQualityAndTokenBudgetIT {

    /**
     * 输入 token 的参考线，<b>不是硬约束</b>。
     *
     * <p>§4.1.5 原先写着「≤60k 硬约束」，2026-09-02 删除：实测 8 页带图 89k~101k，
     * 超了 1.7 倍而链路一路跑通——那个数既不准也从未被执行。这里只用来在报告里
     * 标注「离原估算线多远」，<b>不再据此断言失败</b>。</p>
     *
     * <p>真正约束批次规模的是输出侧：decode ≈106 token/s，
     * {@code llm.extraction.read-timeout-millis} 除以它就是输出 token 的上限。</p>
     */
    private static final int INPUT_TOKEN_REFERENCE_LINE = 60000;

    /** 同一批次重复调用次数；temperature=0 也不保证逐次一致，通过率要多次才有意义。 */
    private static final int DEFAULT_REPEAT = 3;

    /** OCR 档渲染图的等效字节上限，仅用于装配解析链，与本测试的结论无关。 */
    private static final long OCR_IMAGE_BYTES = 8L * 1024L * 1024L;

    private static final int CONNECT_TIMEOUT_MILLIS = 30000;

    /** 默认读超时；生产是 ExtractionProperties.readTimeoutMillis = 180 秒，比这里还短。 */
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 300;

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ModelOutputSchemaRegistry schemaRegistry =
            new ModelOutputSchemaRegistry(objectMapper);

    /**
     * 打开本类 logger 的 INFO。
     * <p>{@code src/test/resources/logback-test.xml} 的 root 是 WARN——那是为了防止第三方 HTTP 栈
     * 打印请求体和响应体（里面有健康数据）。本类的 logger 只输出数量与 token 数，可以放开。</p>
     */
    @BeforeEach
    void enableMeasurementLogs() {
        if (log instanceof ch.qos.logback.classic.Logger) {
            ((ch.qos.logback.classic.Logger) log).setLevel(ch.qos.logback.classic.Level.INFO);
        }
    }

    @Test
    void shouldMeasureSchemaPassRateAndInputTokenBudget() throws Exception {
        Path samplePath = Paths.get(requireEnv("HEALTH_REPORT_SAMPLE"));
        assertThat(samplePath).as("样本文件不存在：" + samplePath).exists();
        int repeat = Integer.parseInt(System.getenv().getOrDefault("HEALTH_REPORT_REPEAT",
                String.valueOf(DEFAULT_REPEAT)));

        List<ExtractionBatchPlan> planList = planBatches(samplePath);
        int maxPages = intEnv("HEALTH_REPORT_MAX_PAGES", 0);
        if (maxPages > 0) {
            // 【诊断用】把每批截到前 N 页，用于二分「超时是图太多还是网关本身慢」。
            // 截断后的 token 数不能当作预算结论，只用来定位。
            log.warn("已启用页数截断：每批只取前 {} 页，本次结果【不可用于 token 预算决策】", maxPages);
            planList = truncatePages(planList, maxPages);
        }
        log.info("样本解析完成：批次数={}，重复次数={}，模型={}", planList.size(), repeat,
                requireEnv("EXTRACTION_MODEL"));

        OpenAiCompatibleExtractionModelClient client = buildClient();
        int totalCalls = planList.size() * (repeat + 1);
        log.info("disableThinking={}", System.getenv()
                .getOrDefault("HEALTH_REPORT_DISABLE_THINKING", "false"));
        log.info("即将串行发起 {} 次调用（{} 批 × ({} 次带图 + 1 次去图））；单次读超时 {} 秒",
                totalCalls, planList.size(), repeat, readTimeoutSeconds());
        List<CallRecord> recordList = new ArrayList<CallRecord>();
        for (ExtractionBatchPlan plan : planList) {
            // 【去图放在前面】它的请求体小一到两个数量级，先跑能最快确认链路是通的；
            // 带图超时而去图正常，就直接定位到页面图是瓶颈。
            recordList.add(invoke(client, withoutImages(plan.getInput()), "去图", 0));
            for (int round = 0; round < repeat; round++) {
                recordList.add(invoke(client, plan.getInput(), "带图", round));
            }
        }

        reportVariantDiff(recordList);
        report(recordList);

        assertThat(recordList).as("一次成功调用都没有，先确认连接参数").anyMatch(record -> record.httpOk);
        // 不再对输入 token 断言：那条硬约束已删除，超出参考线只是需要知道，不是失败。
        for (CallRecord record : recordList) {
            if (record.promptTokens > INPUT_TOKEN_REFERENCE_LINE) {
                log.info("note: batch{} {} promptTokens={} 超过原参考线 {}",
                        record.batchIndex, record.variantAscii(), record.promptTokens,
                        INPUT_TOKEN_REFERENCE_LINE);
            }
        }
    }

    // ==================== 解析与分批：走真实生产链路 ====================

    private List<ExtractionBatchPlan> planBatches(Path samplePath) throws IOException {
        byte[] contentBytes = Files.readAllBytes(samplePath);
        ContentType contentType = contentTypeOf(samplePath);
        TextNormalizer textNormalizer = new TextNormalizer();
        ZipBombGuard zipBombGuard = new ZipBombGuard();
        ImageContentInspector imageContentInspector = new ImageContentInspector();
        PaddleOcrClient ocrClient = unsupportedOcrClient();
        FileParseService parseService = new FileParseService(new PdfTextLayerChecker(),
                new PdfSegmentParser(textNormalizer, new GlyphDensityGate()),
                new PdfPageRenderer(),
                new OfdSegmentParser(textNormalizer, zipBombGuard),
                new WordSegmentParser(textNormalizer, imageContentInspector, ocrClient,
                        new WordCapacityGuard(), zipBombGuard, OCR_IMAGE_BYTES),
                new RenderedPageImageProcessor(new OcrImageEncoder(OCR_IMAGE_BYTES),
                        new ExtractionImageCompressor()),
                new ExtractionImageCompressor(), imageContentInspector,
                new OcrPageSegmentFactory(textNormalizer,
                        new OcrBboxNormalizer(true, true, new ExifOrientationTransform())),
                ocrClient);

        // precheckPages 生产里由上传期一次算定，ParsedFile 要求它与实际页数严格相等；
        // 这里复用同一个 CapacityPrecheckService，不自己数页。
        int precheckPages = new CapacityPrecheckService(
                new WordDocumentInspector(imageContentInspector, zipBombGuard), zipBombGuard)
                .precheckPages(contentBytes, contentType);
        log.info("样本解析入参：类型={}，等效页数={}", contentType, precheckPages);
        ParsedFile parsedFile = parseService.parse(0, contentType, contentBytes, precheckPages);
        ParsePlan parsePlan = new ParsePlan(Collections.singletonList(parsedFile),
                parsedFile.getPageList().size(), parsedFile.getPageList().size());
        return new BatchPlanner(new ExtractionPromptProvider()).plan(parsePlan);
    }

    /**
     * 扫描件需要 OCR 网关，本测试不接。
     * <p>静默返回空识别结果会让通过率变成一个假数字，所以这里直接失败并说清楚原因。</p>
     */
    private PaddleOcrClient unsupportedOcrClient() {
        return new PaddleOcrClient() {
            @Override
            public OcrResult recognize(byte[] encodedImageBytes) {
                throw new AssertionError("样本没有原生文本层，需要走 OCR 路径，而本测试未接 OCR 网关。"
                        + "请改用带原生文本层的电子版报告，或先单独完成 OCR 链路联调");
            }
        };
    }

    private ContentType contentTypeOf(Path samplePath) {
        String fileName = samplePath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".pdf")) {
            return ContentType.PDF;
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return ContentType.JPG;
        }
        if (fileName.endsWith(".png")) {
            return ContentType.PNG;
        }
        if (fileName.endsWith(".ofd")) {
            return ContentType.OFD;
        }
        if (fileName.endsWith(".docx")) {
            return ContentType.DOCX;
        }
        if (fileName.endsWith(".doc")) {
            return ContentType.DOC;
        }
        throw new AssertionError("无法从扩展名判断文件类型：" + fileName);
    }

    /** 复制批次但去掉全部页面图；文本与页序完全不变，差值才只反映图像。 */
    private ExtractionBatchInput withoutImages(ExtractionBatchInput input) {
        List<BatchPage> pageList = new ArrayList<BatchPage>(input.getPageList().size());
        for (BatchPage page : input.getPageList()) {
            pageList.add(new BatchPage(page.getPage(), page.getRenderedText(), null, false));
        }
        return new ExtractionBatchInput(input.getSystemPrompt(), input.getPromptVersion(),
                input.getFileIndex(), input.getBatchIndex(), input.getBatchCount(), pageList);
    }

    // ==================== 调用与判定 ====================

    /**
     * 请求体用<b>生产的</b> {@code buildRequestBody} 组装、content 用<b>生产的</b>
     * {@code extractContent} 提取，测出来的数才等于线上真会发生的事。
     */
    private CallRecord invoke(OpenAiCompatibleExtractionModelClient client,
                              ExtractionBatchInput input, String variant, int round) throws Exception {
        CallRecord record = new CallRecord(input.getBatchIndex(), variant, round);
        byte[] requestBytes = maybeDisableThinking(client.buildRequestBody(input));
        record.requestBytes = requestBytes.length;
        // 单次调用可能到分钟级；不打进度的话，卡住和慢在终端上看不出区别。
        log.info(">> batch{} {} #{} sent, requestBytes={}", input.getBatchIndex(),
                record.variantAscii(), round + 1, record.requestBytes);

        long startedNanos = System.nanoTime();
        HttpResponse response = post(requestBytes);
        record.elapsedMillis = (System.nanoTime() - startedNanos) / 1000000L;
        record.httpStatus = response.statusCode;
        record.httpOk = response.statusCode >= 200 && response.statusCode <= 299;
        if (!record.httpOk) {
            record.failureCategory = "HTTP_" + response.statusCode;
            logProgress(record);
            return record;
        }

        String responseBody = new String(response.bodyBytes, StandardCharsets.UTF_8);
        JsonNode envelopeNode = objectMapper.readTree(responseBody);
        JsonNode choiceNode = envelopeNode.path("choices").path(0);
        record.finishReason = choiceNode.path("finish_reason").asText("(缺失)");
        record.promptTokens = envelopeNode.path("usage").path("prompt_tokens").asInt(-1);
        record.completionTokens = envelopeNode.path("usage").path("completion_tokens").asInt(-1);

        // 只记长度，不记内容：用来判断 17k 输出里有多少是思考段。
        record.contentChars = choiceNode.path("message").path("content").asText("").length();
        record.reasoningChars = choiceNode.path("message").path("reasoning_content").asText("").length();

        String content;
        try {
            content = client.extractContent(responseBody, input.getBatchIndex());
            dumpForReview(content, input.getBatchIndex(), variant, round);
        } catch (RuntimeException exception) {
            record.failureCategory = "无可用 JSON 通道";
            logProgress(record);
            return record;
        }
        record.pickedChars = content.length();
        JsonNode outputNode;
        try {
            outputNode = objectMapper.readTree(content);
        } catch (IOException exception) {
            record.failureCategory = "JSON 解析失败";
            logProgress(record);
            return record;
        }
        record.outputNode = outputNode;
        record.jsonChars = objectMapper.writeValueAsString(outputNode).length();
        record.blockRefsProfile = profileBlockRefs(outputNode);
        Set<ValidationMessage> violationSet = schemaRegistry.extraction().validate(outputNode);
        if (violationSet.isEmpty()) {
            record.schemaPassed = true;
            logProgress(record);
            return record;
        }
        // 只留关键字与 JSON 路径；ValidationMessage 的正文可能带模型输出的值，绝不记录。
        Map<String, Integer> keywordCountMap = new LinkedHashMap<String, Integer>();
        List<String> pathList = new ArrayList<String>();
        for (ValidationMessage violation : violationSet) {
            String keyword = violation.getType();
            Integer previous = keywordCountMap.get(keyword);
            keywordCountMap.put(keyword, previous == null ? 1 : previous.intValue() + 1);
            if (pathList.size() < 8) {
                pathList.add(violation.getType() + " @ " + violation.getPath());
            }
        }
        record.failureCategory = keywordCountMap.toString();
        record.violationPathList = pathList;
        logProgress(record);
        return record;
    }

    /** 单次调用结束即输出结论，长时间运行时不至于对着空终端猜。 */
    private void logProgress(CallRecord record) {
        log.info("<< batch{} {} #{} done: schema={} finish={} promptTokens={} "
                        + "completionTokens={} elapsed={}s {}",
                record.batchIndex, record.variantAscii(), record.round + 1,
                (record.schemaPassed ? "PASS" : "FAIL") + "/prod="
                        + (record.productionPassed ? "PASS" : "FAIL"),
                record.finishReason, record.promptTokens,
                record.completionTokens, record.elapsedMillis / 1000L,
                record.schemaPassed ? "" : record.failureCategory);
        log.info("   channels: contentChars={} reasoningChars={} pickedChars={} jsonChars={}",
                record.contentChars, record.reasoningChars, record.pickedChars, record.jsonChars);
        log.info("   blockRefs: {}", record.blockRefsProfile);
    }

    private HttpResponse post(byte[] requestBytes) throws IOException {
        String url = requireEnv("EXTRACTION_BASE_URL").replaceAll("/+$", "") + "/v1/chat/completions";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(readTimeoutSeconds() * 1000);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + requireEnv("EXTRACTION_API_KEY"));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBytes);
            }
            int statusCode = connection.getResponseCode();
            InputStream bodyStream = statusCode >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            return new HttpResponse(statusCode, readBounded(bodyStream));
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readBounded(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = inputStream.read(chunk)) > 0) {
            if (buffer.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("响应体超过测试读取上限");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private OpenAiCompatibleExtractionModelClient buildClient() {
        ExtractionProperties properties = new ExtractionProperties();
        properties.setBaseUrl(requireEnv("EXTRACTION_BASE_URL"));
        properties.setModel(requireEnv("EXTRACTION_MODEL"));
        properties.setApiKey(requireEnv("EXTRACTION_API_KEY"));
        return new OpenAiCompatibleExtractionModelClient(objectMapper, properties);
    }

    private List<ExtractionBatchPlan> truncatePages(List<ExtractionBatchPlan> planList, int maxPages) {
        List<ExtractionBatchPlan> resultList = new ArrayList<ExtractionBatchPlan>(planList.size());
        for (ExtractionBatchPlan plan : planList) {
            ExtractionBatchInput input = plan.getInput();
            List<BatchPage> pageList = input.getPageList();
            List<BatchPage> truncatedList = new ArrayList<BatchPage>(
                    pageList.subList(0, Math.min(maxPages, pageList.size())));
            resultList.add(new ExtractionBatchPlan(new ExtractionBatchInput(input.getSystemPrompt(),
                    input.getPromptVersion(), input.getFileIndex(), input.getBatchIndex(),
                    input.getBatchCount(), truncatedList), plan.getAddressing()));
        }
        return resultList;
    }

    /**
     * 按需在请求体里叠加「关闭思考」。
     *
     * <p>请求体本身仍由生产的 {@code buildRequestBody} 生成，这里只加一个字段——
     * 其余部分与线上完全一致，测出来的差就只来自思考开关。</p>
     *
     * <p>用的是 vLLM / SGLang 对 Qwen3 的标准写法 {@code chat_template_kwargs}。
     * 网关若不认，会返回 400 而不是静默忽略——那也是需要知道的结论（§0.4）。</p>
     */
    /**
     * 统计各数组里 blockRefs 的实际规模。
     *
     * <p>上限该定多少不该靠猜：2026-09-01 把 summaryConclusions / textualFindings 从 32 放到 128，
     * 依据是两个模型独立撞限；indicators 只撞过一次，保持 32 并靠本统计观察是否也要放宽。</p>
     */
    private String profileBlockRefs(JsonNode outputNode) {
        StringBuilder builder = new StringBuilder();
        for (String arrayField : new String[] {"indicators", "textualFindings", "summaryConclusions"}) {
            int max = 0;
            int total = 0;
            int count = 0;
            for (JsonNode itemNode : outputNode.path(arrayField)) {
                int size = itemNode.path("blockRefs").size();
                max = Math.max(max, size);
                total += size;
                count++;
            }
            if (count > 0) {
                builder.append(arrayField).append("[").append(count).append("条 最大=").append(max)
                        .append(" 均值=").append(total / count).append("] ");
            }
        }
        return builder.toString();
    }

    private byte[] maybeDisableThinking(byte[] requestBytes) throws IOException {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("HEALTH_REPORT_DISABLE_THINKING",
                "false"))) {
            return requestBytes;
        }
        ObjectNode requestNode = (ObjectNode) objectMapper.readTree(requestBytes);
        requestNode.putObject("chat_template_kwargs").put("enable_thinking", false);
        return objectMapper.writeValueAsBytes(requestNode);
    }

    private int readTimeoutSeconds() {
        return intEnv("HEALTH_REPORT_READ_TIMEOUT_SECONDS", DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /**
     * 把模型原始输出转存到仓库外，供人工核对抽取质量。
     *
     * <p><b>这些文件含真实健康数据</b>——姓名、检验值、结论原文。默认关闭；
     * 目录必须显式给出且<b>不得指向仓库内</b>，看完请自行删除。
     * 自动化能算出「差了几条」，但「多出来的那几条是不是真结论」只能人看。</p>
     */
    private void dumpForReview(String content, int batchIndex, String variant, int round) {
        String dumpDir = System.getenv("HEALTH_REPORT_DUMP_DIR");
        if (dumpDir == null || dumpDir.trim().isEmpty()) {
            return;
        }
        try {
            Path dir = Paths.get(dumpDir.trim());
            if (dir.toAbsolutePath().normalize()
                    .startsWith(Paths.get("").toAbsolutePath().normalize())) {
                throw new AssertionError("HEALTH_REPORT_DUMP_DIR 不能指向仓库内：里面是真实健康数据");
            }
            Files.createDirectories(dir);
            Path file = dir.resolve(String.format("batch%d-%s-%d.json", batchIndex,
                    "去图".equals(variant) ? "noimg" : "img", round + 1));
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            log.info("   dumped: {}", file.toAbsolutePath());
        } catch (IOException exception) {
            log.warn("   dump 失败，异常类型={}", exception.getClass().getName());
        }
    }

    /**
     * 同一批次「带图」与「去图」的条目差异，<b>只报数量不报内容</b>。
     *
     * <p>实测出现过「带图反而抽得少」：批次0 的 {@code textualFindings} 去图 11 条、
     * 带图 3 条。少的 8 条是<b>带图漏抽</b>还是<b>去图过度抽取</b>，数量说明不了，
     * 但它能告诉你该去核对哪个数组。</p>
     */
    private void reportVariantDiff(List<CallRecord> recordList) {
        Map<Integer, CallRecord> withoutImageByBatch = new LinkedHashMap<Integer, CallRecord>();
        for (CallRecord record : recordList) {
            if ("去图".equals(record.variant) && record.outputNode != null) {
                withoutImageByBatch.put(Integer.valueOf(record.batchIndex), record);
            }
        }
        for (CallRecord record : recordList) {
            if (!"带图".equals(record.variant) || record.round != 0 || record.outputNode == null) {
                continue;
            }
            CallRecord baseline = withoutImageByBatch.get(Integer.valueOf(record.batchIndex));
            if (baseline == null) {
                continue;
            }
            for (String[] spec : new String[][] {
                    {"indicators", "name", "value"}, {"textualFindings", "title"},
                    {"summaryConclusions", "sourceOrder"}, {"allergens", "enumKey", "rawName"}}) {
                Set<String> noImg = itemKeys(baseline.outputNode, spec);
                Set<String> withImg = itemKeys(record.outputNode, spec);
                Set<String> onlyNoImg = new LinkedHashSet<String>(noImg);
                onlyNoImg.removeAll(withImg);
                Set<String> onlyWithImg = new LinkedHashSet<String>(withImg);
                onlyWithImg.removeAll(noImg);
                if (onlyNoImg.isEmpty() && onlyWithImg.isEmpty()) {
                    continue;
                }
                log.info("   diff batch{} {}: both={} onlyNoImg={} onlyImg={}",
                        record.batchIndex, spec[0],
                        noImg.size() - onlyNoImg.size(), onlyNoImg.size(), onlyWithImg.size());
            }
        }
    }

    /** 用指定字段拼出条目身份键；键本身不输出，只用于求交集与差集。 */
    private Set<String> itemKeys(JsonNode outputNode, String[] spec) {
        Set<String> resultSet = new LinkedHashSet<String>();
        for (JsonNode itemNode : outputNode.path(spec[0])) {
            StringBuilder builder = new StringBuilder();
            for (int index = 1; index < spec.length; index++) {
                builder.append(itemNode.path(spec[index]).asText("")).append('\u0001');
            }
            resultSet.add(builder.toString());
        }
        return resultSet;
    }

    private int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new AssertionError("缺少环境变量 " + name + "；本测试不使用任何默认值");
        }
        return value.trim();
    }

    // ==================== 汇总 ====================

    /**
     * 汇总写成 UTF-8 文件，控制台只打路径。
     *
     * <p>Windows 控制台按系统代码页（GBK）解码，JDK 18+ 起 {@code file.encoding} 已固定 UTF-8，
     * 两者对不上就是满屏方块。与其调 {@code stdout.encoding} 碰运气，不如落成文件——
     * 用 IDE 或记事本打开一定是对的。</p>
     *
     * <p>文件里只有数量、token 数和 JSON 路径，没有报告正文，可以随便传阅。</p>
     */
    private void report(List<CallRecord> recordList) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("模型=").append(requireEnv("EXTRACTION_MODEL")).append('\n');
        builder.append("样本=").append(requireEnv("HEALTH_REPORT_SAMPLE")).append('\n');
        builder.append("读超时=").append(readTimeoutSeconds()).append(" 秒\n");
        builder.append("关闭思考=").append(System.getenv()
                .getOrDefault("HEALTH_REPORT_DISABLE_THINKING", "false")).append('\n');
        int maxPages = intEnv("HEALTH_REPORT_MAX_PAGES", 0);
        if (maxPages > 0) {
            builder.append("【已截断】每批只取前 ").append(maxPages)
                    .append(" 页，本结果不可用于 token 预算决策\n");
        }

        builder.append("\n================ 逐次调用 ================\n");
        for (CallRecord record : recordList) {
            builder.append(String.format("批次%d %s 第%d次 | HTTP=%d finish=%s 输入token=%d "
                            + "输出token=%d 请求字节=%d 耗时=%ds | %s%n",
                    record.batchIndex, record.variant, record.round + 1, record.httpStatus,
                    record.finishReason, record.promptTokens, record.completionTokens,
                    record.requestBytes, record.elapsedMillis / 1000L,
                    record.schemaPassed ? "Schema 通过" : "Schema 失败：" + record.failureCategory));
            builder.append("        剔除明细：").append(record.droppedProfile).append('\n');
            builder.append("        blockRefs 规模：").append(record.blockRefsProfile).append('\n');
            builder.append(String.format("        通道字符数：content=%d reasoning_content=%d "
                            + "选中=%d JSON本身=%d%n",
                    record.contentChars, record.reasoningChars, record.pickedChars,
                    record.jsonChars));
            for (String path : record.violationPathList) {
                builder.append("        违规：").append(path).append('\n');
            }
        }

        int withImageTotal = 0;
        int withImagePassed = 0;
        int withImageProductionPassed = 0;
        long withImageTokenSum = 0L;
        int withImageTokenCount = 0;
        Map<Integer, Integer> withoutImageTokenByBatch = new LinkedHashMap<Integer, Integer>();
        Map<Integer, Integer> withImageTokenByBatch = new LinkedHashMap<Integer, Integer>();
        for (CallRecord record : recordList) {
            if ("去图".equals(record.variant)) {
                withoutImageTokenByBatch.put(record.batchIndex, record.promptTokens);
                continue;
            }
            withImageTotal++;
            if (record.schemaPassed) {
                withImagePassed++;
            }
            if (record.productionPassed) {
                withImageProductionPassed++;
            }
            if (record.promptTokens > 0) {
                withImageTokenSum += record.promptTokens;
                withImageTokenCount++;
                withImageTokenByBatch.put(record.batchIndex, record.promptTokens);
            }
        }

        builder.append("\n================ 汇总 ================\n");
        builder.append(String.format("Schema 原始通过率（不剔除）：%d/%d = %d%%%n",
                withImagePassed, withImageTotal,
                withImageTotal == 0 ? 0 : withImagePassed * 100 / withImageTotal));
        builder.append(String.format("【生产口径】剔除后通过率：%d/%d = %d%%  ← 这个才是线上成败%n",
                withImageProductionPassed, withImageTotal,
                withImageTotal == 0 ? 0 : withImageProductionPassed * 100 / withImageTotal));
        builder.append(String.format("平均输入 token：%d（原参考线 %d，硬约束已于 2026-09-02 删除）%n",
                withImageTokenCount == 0 ? -1 : withImageTokenSum / withImageTokenCount,
                INPUT_TOKEN_REFERENCE_LINE));
        for (Map.Entry<Integer, Integer> entry : withImageTokenByBatch.entrySet()) {
            Integer withoutImage = withoutImageTokenByBatch.get(entry.getKey());
            builder.append(String.format("批次%d 图像 token ≈ %d（带图 %d - 去图 %d）%n",
                    entry.getKey(), withoutImage == null ? -1 : entry.getValue() - withoutImage,
                    entry.getValue(), withoutImage));
        }

        Path reportPath = Paths.get("target", "extraction-token-budget-report.txt");
        Files.createDirectories(reportPath.getParent());
        Files.write(reportPath, builder.toString().getBytes(StandardCharsets.UTF_8));
        log.info("==== report written (UTF-8): {} ====", reportPath.toAbsolutePath());
    }

    private static final class CallRecord {
        private final int batchIndex;
        private final String variant;
        private final int round;
        private int httpStatus;
        private boolean httpOk;
        private String finishReason = "(未调用)";
        private int promptTokens = -1;
        private int completionTokens = -1;
        private int requestBytes;
        private long elapsedMillis;
        private boolean schemaPassed;
        private boolean productionPassed;
        private String droppedProfile = "";
        /** 只在内存里用于同批次两个变体的差异比对，绝不写日志。 */
        private JsonNode outputNode;
        private String failureCategory = "";
        private int contentChars;
        private int reasoningChars;
        private int pickedChars;
        private int jsonChars;
        private String blockRefsProfile = "";
        private List<String> violationPathList = Collections.emptyList();

        /** 控制台用；Windows 代码页下中文会花，进度行一律走 ASCII。 */
        private String variantAscii() {
            return "去图".equals(variant) ? "noimg" : "img";
        }

        private CallRecord(int batchIndex, String variant, int round) {
            this.batchIndex = batchIndex;
            this.variant = variant;
            this.round = round;
        }
    }

    private static final class HttpResponse {
        private final int statusCode;
        private final byte[] bodyBytes;

        private HttpResponse(int statusCode, byte[] bodyBytes) {
            this.statusCode = statusCode;
            this.bodyBytes = bodyBytes;
        }
    }
}
