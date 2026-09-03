package com.example.healthreport.infra;

import com.example.healthreport.parse.ExtractionImageCompressor;
import com.example.healthreport.parse.pdf.PdfPageRenderer;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM-A 纯图像输入探针：PDF → 逐页渲染 → 压缩 JPEG → Base64 → 直连模型 → 校验输出。
 *
 * <p><b>这不是门禁，是一次可重复的能力验证</b>：回答「不给文本块、只给整份报告的页面图，
 * LLM-A 能不能把四个模块所需的数据全部输出」。因此它连真实网关、跑一次要花钱，
 * 打了 {@code image-probe} 标签，默认构建永远跑不到，与仓库既有的
 * {@code pre-release-only} / {@code external-ocr} 是同一套机制。</p>
 *
 * <pre>
 * export EXTRACTION_BASE_URL=https://your-gateway
 * export EXTRACTION_MODEL=your-model
 * export EXTRACTION_API_KEY=sk-xxx
 *
 * mvn -P image-probe test -Dtest=ExtractionImageOnlyProbeTest \
 *     -Dprobe.pdf=/绝对路径/体检报告.pdf
 * </pre>
 *
 * <p>图像一律是 JPEG，因此 data URI 前缀只能是 {@code image/jpeg}——
 * mime 与实际字节不一致时，按字节嗅探的网关能认、按声明走的会直接报错。</p>
 *
 * <p>PDF→图沿用生产组件 {@link PdfPageRenderer} 与 {@link ExtractionImageCompressor}，
 * 响应解析沿用 {@link OpenAiCompatibleExtractionModelClient#extractContent}——
 * 探针与生产链路的差异必须只有「没有文本块」这一个变量，
 * 自己另写一套渲染或解析，测出来的东西就不能用来推断生产行为。</p>
 *
 * <p>断言用 {@link SoftAssertions}：探针要的是<b>一次跑出全部缺陷</b>，不是撞见第一条就停。</p>
 *
 * <p>{@code target/image-only-probe/} 下留三份产物：</p>
 * <pre>
 * probe-raw-response.json  网关原始信封，含 usage / finish_reason / reasoning_content。
 *                          【在任何断言之前写盘】，请求失败或输出被截断时它是唯一的证据
 * probe-output.json        解析出的模型 JSON，格式化后便于与原报告逐条比对
 * probe-summary.txt        条数统计与覆盖页码集合
 * </pre>
 *
 * <p><b>这三份都是真实体检数据</b>，只写在 {@code target/} 里、不入库不进日志，看完自行清理。</p>
 */
@Tag("image-probe")
class ExtractionImageOnlyProbeTest {

    private static final String PROMPT_DEFAULT = "prompt/extraction-image-only-probe.md";
    private static final String OUTPUT_DIR_DEFAULT = "target/image-only-probe";
    private static final String SYSTEM_SECTION_MARKER = "\n## System";
    private static final int MAX_PAGES_DEFAULT = 30;
    private static final int ADVICE_QUOTE_MAX_LENGTH = 100;

    private static final Set<String> TOP_LEVEL_FIELDS = fields("pageCount", "batchStatus",
            "patient", "reportOverview", "sections", "indicators", "textualFindings",
            "summaryConclusions", "allergens", "nutritionSupplements", "dietRequirements",
            "allergenSectionPages", "allergenDataRowCount");
    private static final Set<String> PATIENT_FIELDS =
            fields("name", "namePages", "gender", "genderPages");
    private static final Set<String> OVERVIEW_FIELDS =
            fields("totalCount", "abnormalCount", "page", "sourceText");
    private static final Set<String> SECTION_FIELDS =
            fields("sectionName", "sectionIndex", "sectionRelation", "page");
    private static final Set<String> INDICATOR_FIELDS = fields("name", "value", "unit",
            "refRange", "conclusionText", "conclusionBasis", "rangeComparison", "valueMatch",
            "status", "includeInHealthProblems", "problemName", "sectionIndex",
            "orderInSection", "itemIndex", "page", "sourceText");
    private static final Set<String> FINDING_FIELDS = fields("title", "conclusionText", "status",
            "includeInHealthProblems", "sectionIndex", "orderInSection", "itemIndex",
            "page", "sourceText");
    private static final Set<String> CONCLUSION_FIELDS = fields("sourceOrder", "itemNo",
            "categories", "includeInHealthProblems", "sectionIndex", "itemIndex",
            "page", "sourceText");
    private static final Set<String> ALLERGEN_FIELDS = fields("enumKey", "isFoodBorne", "rawName",
            "rawResult", "resultStatus", "sectionIndex", "sourceOrder", "itemIndex",
            "page", "sourceText");
    private static final Set<String> ADVICE_FIELDS = fields("enumKey", "adviceQuote",
            "applicability", "structuredSafety", "sectionIndex", "sourceOrder", "itemNo",
            "itemIndex", "page");

    private static final Set<String> SECTION_RELATIONS =
            fields("CURRENT", "UNSECTIONED", "UNKNOWN");
    private static final Set<String> INDICATOR_STATUSES = fields("NORMAL", "HIGH", "LOW", "ABNORMAL");
    private static final Set<String> FINDING_STATUSES = fields("NORMAL", "ABNORMAL");
    private static final Set<String> CONCLUSION_BASES =
            fields("REPORT_TEXT", "REFERENCE_RANGE_IN_RANGE", "REFERENCE_VALUE_MATCH");
    private static final Set<String> CATEGORIES = fields("HEALTH_PROBLEM", "DIET_ADVICE",
            "LIFESTYLE", "ROUTINE", "NORMAL_STATEMENT");
    private static final Set<String> RESULT_STATUSES =
            fields("POSITIVE", "NEGATIVE", "BORDERLINE", "UNKNOWN");
    private static final Set<String> APPLICABILITIES = fields("CURRENT_PATIENT", "OTHER_PERSON",
            "GENERAL_INFORMATION", "UNCERTAIN");
    private static final Set<String> SAFETIES = fields("NORMAL", "DIRECTIONAL_RESTRICTION",
            "SPECIAL_POPULATION", "UNCERTAIN");
    private static final Set<String> COMPARABLE_VALUES =
            fields("NEGATIVE", "POSITIVE", "WEAK_POSITIVE", "NOT_DETECTED");
    private static final Set<String> ALLERGEN_ENUMS = fields("SHRIMP_CRAB", "FISH", "MILK", "EGG",
            "PEANUT", "SOY", "WHEAT", "NUTS", "MANGO", "BEEF", "MUTTON", "MOLLUSK", "SESAME",
            "DUST_MITE", "POLLEN", "ANIMAL_DANDER", "MOLD", "COCKROACH", "OTHER");
    private static final Set<String> NUTRITION_ENUMS = fields("IRON", "CALCIUM", "PROTEIN",
            "VITAMIN_D", "VITAMIN_B12", "FOLATE", "DIETARY_FIBER", "ZINC", "POTASSIUM", "OTHER");
    private static final Set<String> DIET_ENUMS = fields("LOW_FAT", "LOW_SODIUM", "LOW_ADDED_SUGAR",
            "LOW_PURINE", "LOW_CHOLESTEROL", "LOW_CALORIE", "HIGH_FIBER", "LIMIT_ALCOHOL",
            "LIGHT_DIET", "OTHER");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TextNormalizer textNormalizer = new TextNormalizer();
    private final SoftAssertions softly = new SoftAssertions();

    @Test
    void imageOnlyExtractionShouldProduceEveryFieldTheFourModulesNeed() throws Exception {
        String pdfPath = ProbeModelCall.setting("probe.pdf", "PROBE_PDF", "");
        String baseUrl = ProbeModelCall.setting("probe.baseUrl", "EXTRACTION_BASE_URL", "");
        String model = ProbeModelCall.setting("probe.model", "EXTRACTION_MODEL", "");
        String apiKey = ProbeModelCall.setting("probe.apiKey", "EXTRACTION_API_KEY", "");
        // 探针不是门禁，没配齐就跳过而不是判失败——它一次要花真金白银，
        // 不该在别人跑标签组时替他们做决定。
        Assumptions.assumeTrue(!pdfPath.isEmpty(),
                "no -Dprobe.pdf given; skipping the image-only probe");
        Assumptions.assumeTrue(!baseUrl.isEmpty() && !model.isEmpty() && !apiKey.isEmpty(),
                "EXTRACTION_BASE_URL / EXTRACTION_MODEL / EXTRACTION_API_KEY not configured; skipping the image-only probe");

        Path pdf = Paths.get(pdfPath);
        org.assertj.core.api.Assertions.assertThat(pdf)
                .as("the PDF to extract must exist").isRegularFile();

        int pageFrom = Math.max(1, ProbeModelCall.intSetting("probe.pageFrom", 1));
        Path outputDir = Paths.get(ProbeModelCall.setting("probe.out", "PROBE_OUT", OUTPUT_DIR_DEFAULT));
        List<byte[]> pageImageList = ProbeModelCall.renderPdfPages(pdf, pageFrom,
                ProbeModelCall.intSetting("probe.maxPages", MAX_PAGES_DEFAULT));
        String prompt = ProbeModelCall.loadPromptBody(
                Paths.get(ProbeModelCall.setting("probe.prompt", "PROBE_PROMPT", PROMPT_DEFAULT)));
        String userText = "这是一份体检报告的全部页面图像，共 " + pageImageList.size()
                + " 张，按报告顺序给出。\n"
                + "第 1 张是第 1 页，依此类推；条目里的 page 字段填的就是这个序号。\n\n"
                + "按 System 中的规则抽取，只输出那一个 JSON 对象。";

        ProbeModelCall.Result result = new ProbeModelCall(baseUrl, model, apiKey, outputDir)
                .call(prompt, pageImageList, userText, ProbeModelCall.intSetting("probe.maxTokens", 32768));
        long elapsedMillis = result.elapsedMillis;
        String rawEnvelope = result.envelope;
        org.assertj.core.api.Assertions.assertThat(result.httpStatus)
                .as("gateway HTTP status; response body saved under " + outputDir).isEqualTo(200);

        // 复用生产解析：finish_reason 非 stop 直接失败，content / reasoning_content 双通道同一套裁决。
        String content = new OpenAiCompatibleExtractionModelClient(objectMapper, properties(baseUrl, model, apiKey))
                .extractContent(rawEnvelope, 0);
        JsonNode root = objectMapper.readTree(content);
        Files.write(outputDir.resolve("probe-output.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));

        verifyTopLevel(root, pageImageList.size());
        verifySections(root);
        verifyIndicators(root, pageImageList.size(), root.path("sections").size());
        verifyTextualFindings(root, pageImageList.size(), root.path("sections").size());
        verifySummaryConclusions(root, pageImageList.size(), root.path("sections").size());
        verifyAllergens(root, pageImageList.size(), root.path("sections").size());
        verifyAdvice(root, "nutritionSupplements", NUTRITION_ENUMS,
                pageImageList.size(), root.path("sections").size());
        verifyAdvice(root, "dietRequirements", DIET_ENUMS,
                pageImageList.size(), root.path("sections").size());

        String summary = buildSummary(root, pageImageList.size(), elapsedMillis);
        Files.write(outputDir.resolve("probe-summary.txt"), summary.getBytes(StandardCharsets.UTF_8));
        System.out.println(summary);
        System.out.println("[probe] parsed output written to " + outputDir.resolve("probe-output.json").toAbsolutePath());

        softly.assertAll();
    }

    // ---------- 调用 ----------

    private ExtractionProperties properties(String baseUrl, String model, String apiKey) {
        ExtractionProperties extractionProperties = new ExtractionProperties();
        extractionProperties.setBaseUrl(baseUrl);
        extractionProperties.setModel(model);
        extractionProperties.setApiKey(apiKey);
        return extractionProperties;
    }

    /** 输出 token 数是这条路线的真瓶颈，先把它打出来，免得把「截断」误读成「抽不全」。 */

    // ---------- ④ 校验 ----------

    private void verifyTopLevel(JsonNode root, int pageCount) {
        softly.assertThat(fieldNames(root))
                .as("top-level fields must match exactly, no more and no less").isEqualTo(TOP_LEVEL_FIELDS);
        softly.assertThat(root.path("batchStatus").asText())
                .as("overall recognition status").isEqualTo("OK");
        softly.assertThat(root.path("pageCount").asInt(-1))
                .as("pageCount must equal the number of images sent").isEqualTo(pageCount);

        JsonNode patient = root.path("patient");
        softly.assertThat(fieldNames(patient)).as("patient fields").isEqualTo(PATIENT_FIELDS);
        assertEvidenceLinked(patient, "name", "namePages");
        assertEvidenceLinked(patient, "gender", "genderPages");

        JsonNode overview = root.path("reportOverview");
        if (!overview.isNull() && !overview.isMissingNode()) {
            softly.assertThat(fieldNames(overview))
                    .as("reportOverview fields").isEqualTo(OVERVIEW_FIELDS);
            softly.assertThat(containsNormalized(overview.path("sourceText").asText(),
                            overview.path("totalCount").asText()))
                    .as("reportOverview.totalCount must actually appear in sourceText").isTrue();
        }

        // 这条对不上就说明过敏原漏抽了行；它直接决定模块四关不关（设计方案 §4.4-②）。
        softly.assertThat(root.path("allergenDataRowCount").asInt(-1))
                .as("allergenDataRowCount must equal the allergens array length")
                .isEqualTo(root.path("allergens").size());
        for (JsonNode page : root.path("allergenSectionPages")) {
            assertPageInRange(page.asInt(-1), pageCount, "allergenSectionPages");
        }

        // 一条指标都没有，探针本身就没有讨论价值了。
        softly.assertThat(root.path("indicators").size())
                .as("indicators must not be empty: the image-only run extracted no indicator at all").isGreaterThan(0);
    }

    private void assertEvidenceLinked(JsonNode patient, String valueField, String pagesField) {
        boolean hasValue = !patient.path(valueField).isNull() && patient.path(valueField).isTextual();
        int evidenceCount = patient.path(pagesField).size();
        softly.assertThat(hasValue ? evidenceCount > 0 : evidenceCount == 0)
                .as("patient." + valueField + " and " + pagesField + " must be linked (self-check 1)")
                .isTrue();
    }

    private void verifySections(JsonNode root) {
        JsonNode sections = root.path("sections");
        softly.assertThat(sections.isArray()).as("sections must be an array").isTrue();
        for (int index = 0; index < sections.size(); index++) {
            JsonNode section = sections.get(index);
            String label = "sections[" + index + "]";
            softly.assertThat(fieldNames(section)).as(label + " fields").isEqualTo(SECTION_FIELDS);
            softly.assertThat(section.path("sectionIndex").asInt(-1))
                    .as(label + ".sectionIndex must equal its array index (self-check 3)").isEqualTo(index);
            softly.assertThat(section.path("sectionRelation").asText())
                    .as(label + ".sectionRelation").isIn(SECTION_RELATIONS.toArray());
            softly.assertThat(section.path("sectionName").asText())
                    .as(label + ".sectionName must not be empty").isNotEmpty();
        }
    }

    private void verifyIndicators(JsonNode root, int pageCount, int sectionCount) {
        JsonNode indicators = root.path("indicators");
        for (int index = 0; index < indicators.size(); index++) {
            JsonNode item = indicators.get(index);
            String label = "indicators[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(INDICATOR_FIELDS);
            assertCommonRefs(item, label, pageCount, sectionCount);
            softly.assertThat(item.path("status").asText())
                    .as(label + ".status").isIn(INDICATOR_STATUSES.toArray());
            softly.assertThat(item.path("orderInSection").asInt(-1))
                    .as(label + ".orderInSection must be non-negative").isGreaterThanOrEqualTo(0);

            // ★ 这一组就是「能不能逐字回原文」——纯图像方案的成败在这里。
            String sourceText = item.path("sourceText").asText();
            for (String field : new String[] {"name", "value", "unit", "refRange",
                    "conclusionText", "problemName"}) {
                assertQuotedFromSource(item, field, sourceText, label);
            }

            String basis = item.path("conclusionBasis").asText();
            softly.assertThat(basis).as(label + ".conclusionBasis").isIn(CONCLUSION_BASES.toArray());
            verifyConclusionBasisExclusivity(item, basis, label);
        }
    }

    /** 自检 ②：三条 conclusionBasis 路径的伴生字段不得串台。 */
    private void verifyConclusionBasisExclusivity(JsonNode item, String basis, String label) {
        boolean hasRange = item.path("rangeComparison").isObject();
        boolean hasMatch = item.path("valueMatch").isObject();
        if ("REPORT_TEXT".equals(basis)) {
            softly.assertThat(item.path("conclusionText").isTextual())
                    .as(label + " REPORT_TEXT requires conclusionText quoted from the report").isTrue();
            softly.assertThat(hasRange || hasMatch)
                    .as(label + " REPORT_TEXT requires both companion objects to be null").isFalse();
            return;
        }
        softly.assertThat(item.path("conclusionText").isNull())
                .as(label + " non-REPORT_TEXT requires conclusionText to be null").isTrue();
        softly.assertThat(item.path("refRange").isTextual())
                .as(label + " non-REPORT_TEXT requires refRange quoted from the report").isTrue();
        softly.assertThat(item.path("status").asText())
                .as(label + " non-REPORT_TEXT fixes status to NORMAL").isEqualTo("NORMAL");
        softly.assertThat(item.path("includeInHealthProblems").asBoolean(true))
                .as(label + " non-REPORT_TEXT must not enter health problems").isFalse();
        softly.assertThat(item.path("problemName").isNull())
                .as(label + " non-REPORT_TEXT requires problemName to be null").isTrue();
        if ("REFERENCE_RANGE_IN_RANGE".equals(basis)) {
            softly.assertThat(hasRange && !hasMatch)
                    .as(label + " must carry rangeComparison only").isTrue();
            verifyRangeComparison(item.path("rangeComparison"), item.path("refRange").asText(), label);
        } else {
            softly.assertThat(hasMatch && !hasRange)
                    .as(label + " must carry valueMatch only").isTrue();
            verifyValueMatch(item.path("valueMatch"), label);
        }
    }

    /** 上下界必须逐字来自 refRange 原文，否则等于凭空报一个能让数值落进去的宽区间。 */
    private void verifyRangeComparison(JsonNode range, String refRange, String label) {
        for (String bound : new String[] {"lowerBound", "upperBound"}) {
            JsonNode value = range.path(bound);
            if (value.isTextual()) {
                softly.assertThat(containsNormalized(refRange, value.asText()))
                        .as(label + "." + bound + " must be quoted verbatim from refRange").isTrue();
            }
        }
        softly.assertThat(range.path("lowerBound").isNull() && range.path("upperBound").isNull())
                .as(label + " lowerBound and upperBound must not both be null").isFalse();
    }

    private void verifyValueMatch(JsonNode valueMatch, String label) {
        softly.assertThat(valueMatch.path("resultComparableValue").asText())
                .as(label + ".resultComparableValue").isIn(COMPARABLE_VALUES.toArray());
        JsonNode acceptable = valueMatch.path("acceptableReferenceValues");
        softly.assertThat(acceptable.size())
                .as(label + ".acceptableReferenceValues must not be empty").isGreaterThan(0);
        for (JsonNode value : acceptable) {
            softly.assertThat(value.asText())
                    .as(label + " acceptable reference value").isIn(COMPARABLE_VALUES.toArray());
        }
    }

    private void verifyTextualFindings(JsonNode root, int pageCount, int sectionCount) {
        JsonNode findings = root.path("textualFindings");
        for (int index = 0; index < findings.size(); index++) {
            JsonNode item = findings.get(index);
            String label = "textualFindings[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(FINDING_FIELDS);
            assertCommonRefs(item, label, pageCount, sectionCount);
            softly.assertThat(item.path("status").asText())
                    .as(label + ".status").isIn(FINDING_STATUSES.toArray());
            String sourceText = item.path("sourceText").asText();
            assertQuotedFromSource(item, "title", sourceText, label);
            assertQuotedFromSource(item, "conclusionText", sourceText, label);
            if ("NORMAL".equals(item.path("status").asText())) {
                softly.assertThat(item.path("includeInHealthProblems").asBoolean(true))
                        .as(label + " NORMAL must not enter health problems").isFalse();
            }
        }
    }

    private void verifySummaryConclusions(JsonNode root, int pageCount, int sectionCount) {
        JsonNode conclusions = root.path("summaryConclusions");
        for (int index = 0; index < conclusions.size(); index++) {
            JsonNode item = conclusions.get(index);
            String label = "summaryConclusions[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(CONCLUSION_FIELDS);
            assertCommonRefs(item, label, pageCount, sectionCount);
            softly.assertThat(item.path("sourceOrder").asInt(-1))
                    .as(label + ".sourceOrder must be non-negative").isGreaterThanOrEqualTo(0);
            JsonNode categories = item.path("categories");
            softly.assertThat(categories.size()).as(label + ".categories must not be empty").isGreaterThan(0);
            boolean problemOrDiet = false;
            for (JsonNode category : categories) {
                softly.assertThat(category.asText()).as(label + " category").isIn(CATEGORIES.toArray());
                problemOrDiet |= "HEALTH_PROBLEM".equals(category.asText())
                        || "DIET_ADVICE".equals(category.asText());
            }
            if (item.path("includeInHealthProblems").asBoolean(false)) {
                softly.assertThat(problemOrDiet)
                        .as(label + " may enter health problems only when categories contain HEALTH_PROBLEM or DIET_ADVICE")
                        .isTrue();
            }
        }
    }

    private void verifyAllergens(JsonNode root, int pageCount, int sectionCount) {
        JsonNode allergens = root.path("allergens");
        for (int index = 0; index < allergens.size(); index++) {
            JsonNode item = allergens.get(index);
            String label = "allergens[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(ALLERGEN_FIELDS);
            assertCommonRefs(item, label, pageCount, sectionCount);
            softly.assertThat(item.path("enumKey").asText())
                    .as(label + ".enumKey").isIn(ALLERGEN_ENUMS.toArray());
            softly.assertThat(item.path("resultStatus").asText())
                    .as(label + ".resultStatus").isIn(RESULT_STATUSES.toArray());
            softly.assertThat(item.path("isFoodBorne").isBoolean())
                    .as(label + ".isFoodBorne must be a boolean").isTrue();
            String sourceText = item.path("sourceText").asText();
            assertQuotedFromSource(item, "rawName", sourceText, label);
            assertQuotedFromSource(item, "rawResult", sourceText, label);
        }
    }

    private void verifyAdvice(JsonNode root, String arrayName, Set<String> enumSet,
                              int pageCount, int sectionCount) {
        JsonNode adviceList = root.path(arrayName);
        for (int index = 0; index < adviceList.size(); index++) {
            JsonNode item = adviceList.get(index);
            String label = arrayName + "[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(ADVICE_FIELDS);
            assertCommonRefs(item, label, pageCount, sectionCount);
            softly.assertThat(item.path("enumKey").asText())
                    .as(label + ".enumKey").isIn(enumSet.toArray());
            softly.assertThat(item.path("applicability").asText())
                    .as(label + ".applicability").isIn(APPLICABILITIES.toArray());
            softly.assertThat(item.path("structuredSafety").asText())
                    .as(label + ".structuredSafety").isIn(SAFETIES.toArray());
            String quote = item.path("adviceQuote").asText("");
            softly.assertThat(quote.isEmpty()).as(label + ".adviceQuote must not be empty").isFalse();
            softly.assertThat(quote.length())
                    .as(label + ".adviceQuote is too long; the safety check would hit unrelated words").isLessThanOrEqualTo(ADVICE_QUOTE_MAX_LENGTH);
            softly.assertThat(item.path("sourceOrder").asInt(-1))
                    .as(label + ".sourceOrder must be non-negative").isGreaterThanOrEqualTo(0);
        }
    }

    /** 每个条目共有的三件事：章节下标存在、页码在范围内、itemIndex 非负（自检 ③④）。 */
    private void assertCommonRefs(JsonNode item, String label, int pageCount, int sectionCount) {
        int sectionIndex = item.path("sectionIndex").asInt(-1);
        softly.assertThat(sectionIndex >= 0 && sectionIndex < sectionCount)
                .as(label + ".sectionIndex=" + sectionIndex + " must point at an existing section").isTrue();
        assertPageInRange(item.path("page").asInt(-1), pageCount, label);
        softly.assertThat(item.path("itemIndex").asInt(-1))
                .as(label + ".itemIndex must be non-negative").isGreaterThanOrEqualTo(0);
    }

    private void assertPageInRange(int page, int pageCount, String label) {
        softly.assertThat(page >= 1 && page <= pageCount)
                .as(label + ".page=" + page + " must fall within 1.." + pageCount).isTrue();
    }

    /**
     * 字段值必须能在 sourceText 里逐字找到。
     * <p>按设计方案 §3.2.3 的 OCR 档放宽：规范化后去掉全部空白再比子串。
     * 纯图像输入等价于 OCR 来源，严格匹配会把「模型读对了但空格排布不同」的正确结果杀掉。</p>
     */
    private void assertQuotedFromSource(JsonNode item, String field, String sourceText, String label) {
        JsonNode value = item.path(field);
        if (!value.isTextual() || value.asText().isEmpty()) {
            return;
        }
        softly.assertThat(containsNormalized(sourceText, value.asText()))
                .as(label + "." + field + "=[" + value.asText() + "] not found in sourceText; "
                        + "the production source-evidence check would drop this item")
                .isTrue();
    }

    private boolean containsNormalized(String container, String candidate) {
        return stripped(container).contains(stripped(candidate));
    }

    private String stripped(String text) {
        return textNormalizer.normalize(text == null ? "" : text)
                .getNormalizedText().replaceAll("\\s+", "");
    }

    // ---------- ⑤ 摘要 ----------

    private String buildSummary(JsonNode root, int pageCount, long elapsedMillis) {
        StringBuilder builder = new StringBuilder();
        builder.append("[probe] image-only extraction result\n");
        builder.append("  pages                ").append(pageCount).append('\n');
        builder.append("  elapsed              ").append(elapsedMillis / 1000L).append(" s\n");
        builder.append("  batchStatus          ").append(root.path("batchStatus").asText()).append('\n');
        builder.append("  patient              name=").append(root.path("patient").path("name").asText("null"))
                .append(" gender=").append(root.path("patient").path("gender").asText("null")).append('\n');
        builder.append("  reportOverview       ")
                .append(root.path("reportOverview").isObject() ? "present" : "null").append('\n');
        for (String arrayName : new String[] {"sections", "indicators", "textualFindings",
                "summaryConclusions", "allergens", "nutritionSupplements", "dietRequirements"}) {
            builder.append(String.format("  %-20s %d items%n", arrayName, root.path(arrayName).size()));
        }
        builder.append("  conclusionBasis      ")
                .append("REPORT_TEXT=").append(countBasis(root, "REPORT_TEXT"))
                .append(" referenceRange=").append(countBasis(root, "REFERENCE_RANGE_IN_RANGE"))
                .append(" referenceValue=").append(countBasis(root, "REFERENCE_VALUE_MATCH")).append('\n');
        builder.append("  healthProblems       ").append(countIncluded(root)).append(" items\n");
        builder.append("  pages covered        ").append(coveredPages(root)).append('\n');
        builder.append("  NOTE: whether anything was missed can only be judged by hand against the\n        original report. The counts above only say what the model claimed.\n");
        return builder.toString();
    }

    private int countBasis(JsonNode root, String basis) {
        int count = 0;
        for (JsonNode item : root.path("indicators")) {
            if (basis.equals(item.path("conclusionBasis").asText())) {
                count++;
            }
        }
        return count;
    }

    private int countIncluded(JsonNode root) {
        int count = 0;
        for (String arrayName : new String[] {"indicators", "textualFindings", "summaryConclusions"}) {
            for (JsonNode item : root.path(arrayName)) {
                if (item.path("includeInHealthProblems").asBoolean(false)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 哪些页一条都没产出，是「跳页」最直接的证据（提示词铁律 7）。 */
    private Set<Integer> coveredPages(JsonNode root) {
        Set<Integer> pages = new java.util.TreeSet<Integer>();
        for (String arrayName : new String[] {"indicators", "textualFindings",
                "summaryConclusions", "allergens", "nutritionSupplements", "dietRequirements"}) {
            for (JsonNode item : root.path(arrayName)) {
                pages.add(item.path("page").asInt(-1));
            }
        }
        return pages;
    }

    // ---------- 杂项 ----------

    private static Set<String> fields(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<String>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }

    private long totalBytes(List<byte[]> pageImageList) {
        long total = 0L;
        for (byte[] bytes : pageImageList) {
            total += bytes.length;
        }
        return total;
    }

    private String setting(String systemProperty, String environmentName, String defaultValue) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environmentName);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private boolean booleanSetting(String systemProperty) {
        return Boolean.parseBoolean(System.getProperty(systemProperty, "false"));
    }

    private int intSetting(String systemProperty, int defaultValue) {
        String value = System.getProperty(systemProperty);
        return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
    }
}
