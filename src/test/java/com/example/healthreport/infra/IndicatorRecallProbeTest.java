package com.example.healthreport.infra;

import com.example.healthreport.support.text.TextNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 健康指标召回率探针：PDF → 全页图 → LLM-A → 只抽健康指标 → 与人工真值比召回。
 *
 * <p><b>它只回答一个问题：只给图，模型能把报告里的检验项目找回多少。</b>
 * 不测健康问题、饮食建议、菜品推荐——那三个模块的召回是另外三件事，
 * 混在一起测不出是哪一环丢的（提示词与 Schema 也因此只保留最小字段集）。</p>
 *
 * <pre>
 * export EXTRACTION_BASE_URL=https://your-gateway
 * export EXTRACTION_MODEL=your-model
 * export EXTRACTION_API_KEY=sk-xxx
 *
 * # 第一次跑：没有真值，只输出模型抽到的清单
 * mvn -P image-probe test -Dtest=IndicatorRecallProbeTest -Dprobe.pdf=/abs/report.pdf
 *
 * # 把 target/indicator-recall/indicators.tsv 对着报告改成真值，然后
 * mvn -P image-probe test -Dtest=IndicatorRecallProbeTest \
 *     -Dprobe.pdf=/abs/report.pdf -Dprobe.truth=/abs/truth.tsv -Dprobe.minRecall=0.95
 * </pre>
 *
 * <p><b>真值文件格式</b>：每行一条，制表符分隔 {@code 指标名<TAB>结果}；
 * 只写一列时按指标名匹配。{@code #} 开头是注释，空行忽略。
 * 第一次运行产出的 {@code indicators.tsv} 就是这个格式——<b>照着报告改完即可当真值用</b>，
 * 这比从零手敲一份快得多，也不会漏掉字段格式。</p>
 *
 * <p><b>为什么把「名字对上但值不同」单列一档。</b> 漏读和读错是两种完全不同的失败：
 * 前者是模型跳过了整行，后者是它看错了一个数字。混成一个「未命中」，
 * 会让人误以为要加强「别漏」的提示词，而真正该改的是分辨率或渲染档位。</p>
 */
@Tag("image-probe")
class IndicatorRecallProbeTest {

    private static final String PROMPT_DEFAULT = "prompt/indicators-probe.md";
    private static final String SCHEMA_DEFAULT = "schema/indicators_probe.schema.json";
    private static final String OUTPUT_DIR_DEFAULT = "target/indicator-recall";
    private static final Set<String> ITEM_FIELDS = fields("name", "value", "unit",
            "refRange", "conclusionGenerated", "status");
    private static final Set<String> SECTION_FIELDS = fields("section", "page", "indicators");
    private static final Set<String> TOP_FIELDS = fields("reportStatus", "overview", "sections");
    private static final Set<String> OVERVIEW_FIELDS = fields("totalCount", "abnormalCount", "source");
    private static final Set<String> STATUSES = fields("NORMAL", "HIGH", "LOW", "ABNORMAL");

    /**
     * 出现在「结果」列里就说明抽错了的评价词。
     *
     * <p>「外科：正常」「耳鼻喉科：未见异常」这类是<b>科室小结</b>——评价的是一个部位或科室，
     * 不是某个测出来的项目。需求 §5-2（第 57 行）把「仅给文字结论但无数值」归到健康问题模块，
     * 抽进本模块属于串模块，不是召回率的功劳。</p>
     */
    private static final Set<String> EVALUATION_WORDS = fields("正常", "未见异常", "无异常",
            "未见明显异常", "无殊", "阴性(-)未见异常");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TextNormalizer textNormalizer = new TextNormalizer();
    private final SoftAssertions softly = new SoftAssertions();

    @Test
    void indicatorRecallOnImageOnlyInput() throws Exception {
        String pdfPath = ProbeModelCall.setting("probe.pdf", "PROBE_PDF", "");
        String baseUrl = ProbeModelCall.setting("probe.baseUrl", "EXTRACTION_BASE_URL", "");
        String model = ProbeModelCall.setting("probe.model", "EXTRACTION_MODEL", "");
        String apiKey = ProbeModelCall.setting("probe.apiKey", "EXTRACTION_API_KEY", "");
        Assumptions.assumeTrue(!pdfPath.isEmpty(), "no -Dprobe.pdf given; skipping the recall probe");
        Assumptions.assumeTrue(!baseUrl.isEmpty() && !model.isEmpty() && !apiKey.isEmpty(),
                "EXTRACTION_BASE_URL / EXTRACTION_MODEL / EXTRACTION_API_KEY not configured;"
                        + " skipping the recall probe");

        Path pdf = Paths.get(pdfPath);
        org.assertj.core.api.Assertions.assertThat(pdf).as("the PDF to extract must exist").isRegularFile();
        Path outputDir = Paths.get(ProbeModelCall.setting("probe.out", "PROBE_OUT", OUTPUT_DIR_DEFAULT));
        Files.createDirectories(outputDir);

        List<byte[]> pageImageList = ProbeModelCall.renderPdfPages(pdf,
                Math.max(1, ProbeModelCall.intSetting("probe.pageFrom", 1)),
                ProbeModelCall.intSetting("probe.maxPages", 30));
        String prompt = ProbeModelCall.loadPromptBody(
                Paths.get(ProbeModelCall.setting("probe.prompt", "PROBE_PROMPT", PROMPT_DEFAULT)));
        String userText = "这是一份体检报告的全部页面图像，共 " + pageImageList.size()
                + " 张，按报告顺序给出。\n"
                + "第 1 张是第 1 页，依此类推；条目里的 page 字段填的就是这个序号。\n\n"
                + "把报告里每一个有检测结果的项目都抽出来，正常项和异常项都要，一条都不能漏。\n"
                + "只输出那一个 JSON 对象。";

        ProbeModelCall.Result result = new ProbeModelCall(baseUrl, model, apiKey, outputDir)
                .call(prompt, pageImageList, userText, ProbeModelCall.intSetting("probe.maxTokens", 32768));
        org.assertj.core.api.Assertions.assertThat(result.httpStatus)
                .as("gateway HTTP status; response body saved under " + outputDir).isEqualTo(200);

        // 复用生产解析：finish_reason 非 stop 判死、content / reasoning_content 双通道同一套裁决。
        String content = new OpenAiCompatibleHealthReportAnalysisModelClient(objectMapper, properties(baseUrl, model, apiKey))
                .extractContent(result.envelope, "PROBE");
        JsonNode root = objectMapper.readTree(content);
        Files.write(outputDir.resolve("indicators.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));

        validateAgainstSchema(root, outputDir);
        validateShape(root, pageImageList.size());
        List<Indicator> extracted = readIndicators(root);
        Files.write(outputDir.resolve("indicators.tsv"), toTsv(extracted).getBytes(StandardCharsets.UTF_8));

        String report = buildRecallReport(extracted, root, pageImageList.size());
        Files.write(outputDir.resolve("recall-report.txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
        System.out.println("[probe] extracted list written to "
                + outputDir.resolve("indicators.tsv").toAbsolutePath());
        softly.assertAll();
    }

    // ---------- 校验 ----------

    private void validateAgainstSchema(JsonNode root, Path outputDir) throws IOException {
        Path schemaPath = Paths.get(ProbeModelCall.setting("probe.schema", "PROBE_SCHEMA", SCHEMA_DEFAULT));
        org.assertj.core.api.Assertions.assertThat(schemaPath).as("schema file must exist").isRegularFile();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(objectMapper.readTree(Files.readAllBytes(schemaPath)));
        Set<ValidationMessage> messages = schema.validate(root);
        List<String> paths = new ArrayList<String>();
        for (ValidationMessage message : messages) {
            // 【只记路径与关键字，不记 ValidationMessage 正文】它会带上实际取值，
            // 而那是患者姓名与检验值；生产链路同样只记路径（§4.4-①）。
            paths.add(message.getPath() + " (" + message.getType() + ")");
        }
        softly.assertThat(paths).as("output must satisfy " + schemaPath).isEmpty();
    }

    private void validateShape(JsonNode root, int pageCount) {
        softly.assertThat(fieldNames(root)).as("top-level fields must match exactly").isEqualTo(TOP_FIELDS);
        softly.assertThat(root.path("reportStatus").asText()).as("reportStatus").isEqualTo("OK");
        JsonNode sections = root.path("sections");
        softly.assertThat(sections.size())
                .as("sections must not be empty: the image-only run found nothing").isGreaterThan(0);

        // 同一个章节必须并成一条：页面上一个章节就是一张卡片，重复出现会渲染成两张。
        Set<String> seenSections = new HashSet<String>();
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            JsonNode section = sections.get(sectionIndex);
            String sectionLabel = "sections[" + sectionIndex + "]";
            softly.assertThat(fieldNames(section)).as(sectionLabel + " fields").isEqualTo(SECTION_FIELDS);
            String sectionName = section.path("section").isTextual()
                    ? section.path("section").asText() : "(null)";
            softly.assertThat(seenSections.add(key(sectionName)))
                    .as(sectionLabel + " section=" + sectionName
                            + " appears more than once; a cross-page continuation must be merged")
                    .isTrue();
            int page = section.path("page").asInt(-1);
            softly.assertThat(page >= 1 && page <= pageCount)
                    .as(sectionLabel + ".page=" + page + " must fall within 1.." + pageCount).isTrue();
            softly.assertThat(section.path("indicators").size())
                    .as(sectionLabel + " section=" + sectionName + " has no indicators").isGreaterThan(0);
            validateIndicators(section.path("indicators"), sectionLabel + " section=" + sectionName);
        }
    }

    /**
     * 需求 §5-5 的总览条。
     *
     * <p><b>两个数字一律直接采信，不与抽取结果交叉核对。</b> 报告口径与展示口径本来就不同
     * ——报告的「共检查87项」多半把身高、体重、血压这些按产品口径不展示的体格测量也算了进去，
     * 拿它去卡抽取条数只会得到一堆假警报。</p>
     *
     * <p>差额照旧打印：它不是判据，但仍是漏抽量的一个粗略参考。</p>
     */
    private void validateOverview(JsonNode root, List<Indicator> extracted, StringBuilder builder) {
        JsonNode overview = root.path("overview");
        softly.assertThat(overview.isObject())
                .as("overview must be present: requirement 5-5 needs the summary bar").isTrue();
        if (!overview.isObject()) {
            return;
        }
        softly.assertThat(fieldNames(overview)).as("overview fields").isEqualTo(OVERVIEW_FIELDS);
        softly.assertThat(overview.path("source").asText("")).as("overview.source")
                .isIn("REPORT", "COUNTED");

        int totalCount = overview.path("totalCount").asInt(-1);
        int abnormalCount = overview.path("abnormalCount").asInt(-1);
        int extractedTotal = extracted.size();
        int extractedAbnormal = countAbnormal(extracted);
        builder.append("  overview             ").append("total=").append(totalCount)
                .append(" abnormal=").append(abnormalCount)
                .append(" source=").append(overview.path("source").asText(""))
                .append("   (taken as given, not cross-checked)\n");
        builder.append("  vs extracted         ").append(extractedTotal).append(" items, ")
                .append(extractedAbnormal).append(" abnormal");
        if (totalCount > 0) {
            builder.append(String.format("   -> gap %+d (%.0f%% of the overview count)",
                    extractedTotal - totalCount, 100D * extractedTotal / totalCount));
        }
        builder.append("   informational only\n");
    }

    /**
     * 体格测量项，按产品口径不进健康指标模块。
     *
     * <p><b>只报告，不断言。</b> 判据是名称匹配，而名称在不同报告里写法千差万别
     * （「体重指数」「BMI」「身体质量指数」）——用一张词表去判失败，
     * 会把词表的不完整算到模型头上。这里只把可疑项列出来，改提示词的依据是这份清单。</p>
     *
     * <p>另一层原因：血压、BMI 报告上常印着参考范围，按需求 §5-2 的参考值准入
     * 本来是合规的。排除它们是产品口径，不是需求条文，因此不该以失败的方式表达。</p>
     */
    private static final Set<String> PHYSICAL_MEASUREMENTS = fields("身高", "体重", "bmi",
            "体重指数", "身体质量指数", "腰围", "臀围", "腰臀比", "血压", "收缩压", "舒张压",
            "脉搏", "心率", "体温", "视力", "裸眼视力", "矫正视力", "左眼视力", "右眼视力",
            "听力", "色觉", "辨色力");

    private List<Indicator> physicalMeasurements(List<Indicator> list) {
        List<Indicator> flagged = new ArrayList<Indicator>();
        for (Indicator indicator : list) {
            if (PHYSICAL_MEASUREMENTS.contains(key(indicator.name))) {
                flagged.add(indicator);
            }
        }
        return flagged;
    }

    private int countAbnormal(List<Indicator> list) {
        int count = 0;
        for (Indicator indicator : list) {
            if (indicator.status != null && !"NORMAL".equals(indicator.status)) {
                count++;
            }
        }
        return count;
    }

    private void validateIndicators(JsonNode indicators, String sectionLabel) {
        for (int index = 0; index < indicators.size(); index++) {
            JsonNode item = indicators.get(index);
            String label = sectionLabel + " indicators[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(ITEM_FIELDS);
            softly.assertThat(item.path("name").asText("").isEmpty())
                    .as(label + ".name must not be empty").isFalse();
            softly.assertThat(item.path("value").asText("").isEmpty())
                    .as(label + ".value must not be empty").isFalse();
            // 需求 §5-3 第 82 行：每张卡片都要有状态标签，所以这个字段一条都不能缺。
            softly.assertThat(item.path("status").isTextual())
                    .as(label + ".status must be present: requirement 5-3 needs a label on every card")
                    .isTrue();
            if (item.path("status").isTextual()) {
                softly.assertThat(item.path("status").asText()).as(label + ".status").isIn(STATUSES.toArray());
            }
            boolean generated = item.path("conclusionGenerated").asBoolean(false);
            // 需求 §5-2：报告未给结论、结果又超出参考范围的，整条不展示——
            // 所以走参考值准入的条目只可能是 NORMAL。给出 HIGH/LOW/ABNORMAL 等于
            // 系统生成了一个报告里不存在的异常结论。这是模块一唯一的防幻觉判据。
            if (generated && item.path("status").isTextual()) {
                softly.assertThat(item.path("status").asText())
                        .as(label + " name=" + item.path("name").asText("")
                                + " has conclusionGenerated=true, so status may only be NORMAL;"
                                + " an out-of-range row must be dropped, not relabelled")
                        .isEqualTo("NORMAL");
            }
            // 需求 §5-2 第 56 行：仅列数值、无结论【也无参考值】的指标不在本模块展示。
            // 心率、身高、体重这类一般检查项最常是这个形态——孤零零一个数，
            // 既没有报告的结论也没有比较依据，谁也判断不了正常与否。
            softly.assertThat(generated && item.path("refRange").isNull())
                    .as(label + " name=" + item.path("name").asText("")
                            + " has conclusionGenerated=true but no refRange;"
                            + " nothing could have decided its status (requirement 5-2)")
                    .isFalse();
            // 需求 §5-2 第 57 行：只有文字结论、没有数值的，归健康问题模块。
            softly.assertThat(EVALUATION_WORDS.contains(key(item.path("value").asText(""))))
                    .as(label + " name=" + item.path("name").asText("")
                            + " value=" + item.path("value").asText("")
                            + " is an evaluation, not a measured result;"
                            + " requirement 5-2 sends it to the health-problem module")
                    .isFalse();
        }
    }

    // ---------- 召回率 ----------

    private String buildRecallReport(List<Indicator> extracted, JsonNode root, int pageCount)
            throws IOException {
        // 总览核算写在报告里，与其余统计并列——它既是校验也是漏抽信号。
        StringBuilder builder = new StringBuilder();
        builder.append("[probe] indicator recall report\n");
        builder.append("  pages sent           ").append(pageCount).append('\n');
        builder.append("  extracted            ").append(extracted.size()).append(" indicators\n");
        builder.append("  per section          ").append(perSectionCounts(extracted)).append('\n');
        builder.append("  section start pages  ").append(perPageCounts(extracted)).append('\n');
        builder.append("  conclusion printed   ").append(countFromReport(extracted))
                .append(" of ").append(extracted.size())
                .append("   (the rest carry conclusionGenerated=true: admitted by reference range)\n");
        builder.append("  status spread        ").append(statusSpread(extracted)).append('\n');
        validateOverview(root, extracted, builder);
        List<Indicator> physical = physicalMeasurements(extracted);
        builder.append("  physical measurements ").append(physical.size())
                .append("  (height/weight/BP etc.: product scope keeps them out of this module)\n");
        appendList(builder, "PHYSICAL MEASUREMENTS EXTRACTED", physical);
        builder.append("  no conclusion+refRange ").append(countUnjudgeable(extracted))
                .append("  (requirement 5-2 line 56: must be 0)\n");
        builder.append("  evaluation as value  ").append(countEvaluationValues(extracted))
                .append("  (requirement 5-2 line 57: must be 0)\n");

        String truthPath = ProbeModelCall.setting("probe.truth", "PROBE_TRUTH", "");
        if (truthPath.isEmpty()) {
            builder.append("\n  NO TRUTH FILE (-Dprobe.truth) -> recall not computed.\n");
            builder.append("  Fix indicators.tsv against the paper report and pass it back as the truth file.\n");
            return builder.toString();
        }

        List<Indicator> expected = readTruth(Paths.get(truthPath));
        softly.assertThat(expected.size()).as("truth file must not be empty").isGreaterThan(0);
        List<Indicator> pool = new ArrayList<Indicator>(extracted);
        List<Indicator> matched = new ArrayList<Indicator>();
        List<Indicator> valueMismatch = new ArrayList<Indicator>();
        List<Indicator> missing = new ArrayList<Indicator>();
        for (Indicator truth : expected) {
            Indicator exact = takeMatch(pool, truth, true);
            if (exact != null) {
                matched.add(truth);
                continue;
            }
            Indicator byName = takeMatch(pool, truth, false);
            if (byName != null) {
                valueMismatch.add(new Indicator(byName.page, byName.section, truth.name,
                        truth.value + " -> " + byName.value, null, null, false, null));
            } else {
                missing.add(truth);
            }
        }
        double recall = (double) matched.size() / expected.size();
        double minRecall = doubleSetting("probe.minRecall", 0.9D);

        builder.append("\n  expected (truth)     ").append(expected.size()).append('\n');
        builder.append("  matched              ").append(matched.size()).append('\n');
        builder.append("  value mismatch       ").append(valueMismatch.size())
                .append("   (name found, value differs -> misread, not missed)\n");
        builder.append("  missing              ").append(missing.size()).append('\n');
        builder.append("  extra                ").append(pool.size())
                .append("   (extracted but not in truth; check whether the truth file is incomplete)\n");
        builder.append(String.format("  RECALL               %.3f  (threshold %.3f)%n", recall, minRecall));
        appendList(builder, "MISSING", missing);
        appendList(builder, "VALUE MISMATCH", valueMismatch);
        appendList(builder, "EXTRA", pool);

        softly.assertThat(recall)
                .as("indicator recall must reach -Dprobe.minRecall; see recall-report.txt")
                .isGreaterThanOrEqualTo(minRecall);
        return builder.toString();
    }

    /** 命中即从池中取走，避免同名多条被同一条真值重复命中。 */
    private Indicator takeMatch(List<Indicator> pool, Indicator truth, boolean matchValue) {
        for (int index = 0; index < pool.size(); index++) {
            Indicator candidate = pool.get(index);
            if (!key(candidate.name).equals(key(truth.name))) {
                continue;
            }
            if (matchValue && truth.value != null && !truth.value.isEmpty()
                    && !key(candidate.value).equals(key(truth.value))) {
                continue;
            }
            return pool.remove(index);
        }
        return null;
    }

    private void appendList(StringBuilder builder, String title, List<Indicator> list) {
        if (list.isEmpty()) {
            return;
        }
        builder.append("\n  ").append(title).append(" (").append(list.size()).append(")\n");
        for (Indicator indicator : list) {
            builder.append("    ").append(indicator.name).append('\t')
                    .append(indicator.value == null ? "" : indicator.value);
            if (indicator.page > 0) {
                builder.append("\tp").append(indicator.page);
            }
            builder.append('\n');
        }
    }

    /** 比较前统一规范化并去掉全部空白：全角/半角、NFKC 差异不该算成漏抽。 */
    private String key(String text) {
        return textNormalizer.normalize(text == null ? "" : text).replaceAll("\\s+", "").toLowerCase();
    }

    // ---------- 读写 ----------

    /** 摊平成条目列表；章节名与页码从所属章节带下来，后续统计与匹配都按条目算。 */
    private List<Indicator> readIndicators(JsonNode root) {
        List<Indicator> list = new ArrayList<Indicator>();
        for (JsonNode section : root.path("sections")) {
            int page = section.path("page").asInt(0);
            String sectionName = text(section, "section");
            for (JsonNode item : section.path("indicators")) {
                list.add(new Indicator(page, sectionName, text(item, "name"), text(item, "value"),
                        text(item, "unit"), text(item, "refRange"),
                        item.path("conclusionGenerated").asBoolean(false), text(item, "status")));
            }
        }
        return list;
    }

    private List<Indicator> readTruth(Path truthPath) throws IOException {
        org.assertj.core.api.Assertions.assertThat(truthPath).as("truth file must exist").isRegularFile();
        List<Indicator> list = new ArrayList<Indicator>();
        for (String line : Files.readAllLines(truthPath, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            String name = columns[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            String value = columns.length > 1 ? columns[1].trim() : "";
            list.add(new Indicator(0, null, name, value, null, null, false, null));
        }
        return list;
    }

    /** 与真值文件同一种格式，改完即可回填当真值用。 */
    private String toTsv(List<Indicator> list) {
        StringBuilder builder = new StringBuilder();
        builder.append("# name\tvalue\tunit\trefRange\tconclusionGenerated\tsection\tpage\n");
        builder.append("# 前两列参与匹配，其余列仅供人工核对；改完可直接作为 -Dprobe.truth 传回\n");
        for (Indicator indicator : list) {
            builder.append(nullToEmpty(indicator.name)).append('\t')
                    .append(nullToEmpty(indicator.value)).append('\t')
                    .append(nullToEmpty(indicator.unit)).append('\t')
                    .append(nullToEmpty(indicator.refRange)).append('\t')
                    .append(indicator.conclusionGenerated).append('\t')
                    .append(nullToEmpty(indicator.section)).append('\t')
                    .append(indicator.page).append('\n');
        }
        return builder.toString();
    }

    /** 每个章节抽到几条——某个章节条数异常地少，通常是那张表没读完。 */
    private String perSectionCounts(List<Indicator> list) {
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        for (Indicator indicator : list) {
            String name = indicator.section == null ? "(null)" : indicator.section;
            Integer previous = counts.get(name);
            counts.put(name, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    private String perPageCounts(List<Indicator> list) {
        TreeMap<Integer, Integer> counts = new TreeMap<Integer, Integer>();
        for (Indicator indicator : list) {
            Integer previous = counts.get(indicator.page);
            counts.put(indicator.page, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    /** 四个状态各多少条——正常项占绝大多数，异常项异常地多通常意味着读错了参考范围。 */
    private String statusSpread(List<Indicator> list) {
        TreeMap<String, Integer> counts = new TreeMap<String, Integer>();
        for (Indicator indicator : list) {
            String status = indicator.status == null ? "(null)" : indicator.status;
            Integer previous = counts.get(status);
            counts.put(status, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    /** 既无结论又无参考值的条目数——需求 §5-2 第 56 行要求它是 0。 */
    private int countUnjudgeable(List<Indicator> list) {
        int count = 0;
        for (Indicator indicator : list) {
            if (indicator.conclusionGenerated && isBlank(indicator.refRange)) {
                count++;
            }
        }
        return count;
    }

    /** 结果列填了评价词的条目数——需求 §5-2 第 57 行要求它是 0。 */
    private int countEvaluationValues(List<Indicator> list) {
        int count = 0;
        for (Indicator indicator : list) {
            if (EVALUATION_WORDS.contains(key(indicator.value))) {
                count++;
            }
        }
        return count;
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /** 报告自己印了结论的条数——其余的都是靠参考范围比出来的。 */
    private int countFromReport(List<Indicator> list) {
        int count = 0;
        for (Indicator indicator : list) {
            if (!indicator.conclusionGenerated) {
                count++;
            }
        }
        return count;
    }

    // ---------- 杂项 ----------

    private static final class Indicator {
        private final int page;
        private final String section;
        private final String name;
        private final String value;
        private final String unit;
        private final String refRange;
        private final boolean conclusionGenerated;
        private final String status;

        private Indicator(int page, String section, String name, String value, String unit,
                          String refRange, boolean conclusionGenerated, String status) {
            this.page = page;
            this.section = section;
            this.name = name;
            this.value = value;
            this.unit = unit;
            this.refRange = refRange;
            this.conclusionGenerated = conclusionGenerated;
            this.status = status;
        }
    }

    private HealthReportAnalysisModelProperties properties(String baseUrl, String model, String apiKey) {
        HealthReportAnalysisModelProperties extractionProperties = new HealthReportAnalysisModelProperties();
        extractionProperties.setBaseUrl(baseUrl);
        extractionProperties.setModel(model);
        extractionProperties.setApiKey(apiKey);
        return extractionProperties;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private double doubleSetting(String systemProperty, double defaultValue) {
        String value = System.getProperty(systemProperty);
        return value == null || value.trim().isEmpty() ? defaultValue : Double.parseDouble(value.trim());
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<String>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }

    private static Set<String> fields(String... values) {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
