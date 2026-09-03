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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 健康问题召回率探针：PDF → 全页图 → LLM-A → 只汇总健康问题 → 与人工真值比召回。
 *
 * <p>对应需求 §6（第 110~137 行）。<b>只回答一个问题：只给图，模型能把报告已经说出口的
 * 异常结论与健康提示找回多少。</b> 不测指标卡片、饮食建议、菜品推荐——
 * 那几个模块的召回是另外几件事，混在一起测不出是哪一环丢的。</p>
 *
 * <pre>
 * export EXTRACTION_BASE_URL=https://your-gateway
 * export EXTRACTION_MODEL=your-model
 * export EXTRACTION_API_KEY=sk-xxx
 *
 * # 第一次跑：没有真值，只输出模型汇总到的清单
 * mvn -P image-probe test -Dtest=HealthProblemRecallProbeTest -Dprobe.pdf=/abs/report.pdf
 *
 * # 把 target/health-problem-recall/problems.tsv 对着报告改成真值，然后
 * mvn -P image-probe test -Dtest=HealthProblemRecallProbeTest \
 *     -Dprobe.pdf=/abs/report.pdf -Dprobe.truth=/abs/truth.tsv -Dprobe.minRecall=0.95
 * </pre>
 *
 * <p><b>真值文件格式</b>：每行一条，制表符分隔 {@code 问题名称<TAB>来源类型}；
 * 只写一列时只按名称匹配。{@code #} 开头是注释，空行忽略。
 * 第一次运行产出的 {@code problems.tsv} 就是这个格式，照着报告改完即可回填。</p>
 *
 * <p><b>「漏掉」和「改写」分成两档。</b> 名称对不上但原文对得上，多半是模型把
 *「脂肪肝」写成了「肝脏脂肪浸润」——需求 §6-3 明令不得改写，但它和「整条没看见」
 * 是两种病：前者改提示词的引用约束，后者改覆盖度。</p>
 */
@Tag("image-probe")
class HealthProblemRecallProbeTest {

    private static final String PROMPT_DEFAULT = "prompt/health-problems-probe.md";
    private static final String SCHEMA_DEFAULT = "schema/health_problems_probe.schema.json";
    private static final String OUTPUT_DIR_DEFAULT = "target/health-problem-recall";
    private static final Set<String> TOP_FIELDS = fields("reportStatus", "problems");
    private static final Set<String> ITEM_FIELDS = fields("sourceType", "page", "section",
            "itemNo", "indicatorName", "name", "rawText");
    private static final Set<String> SOURCE_TYPES = fields("INDICATOR", "SUMMARY");

    /**
     * 出现在 {@code name} 里就说明收错了的正常表述。
     *
     * <p>需求 §6-1 只汇总「明确给出的异常结论和健康提示」，§6-5 还专门为「全部正常」
     * 定义了空态文案——把「未见明显异常」收成一条健康问题，页面上就会在
     *「健康问题」标题下显示一条正常结论。</p>
     */
    private static final Set<String> NORMAL_STATEMENTS = fields("正常", "未见异常", "无异常",
            "未见明显异常", "各项检查未见明显异常", "无殊", "阴性");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TextNormalizer textNormalizer = new TextNormalizer();
    private final SoftAssertions softly = new SoftAssertions();

    @Test
    void healthProblemRecallOnImageOnlyInput() throws Exception {
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
                + "把报告中【明确给出的异常结论和健康提示】汇总出来：\n"
                + "检验表里被标注为异常的指标、以及总检结论／医生建议里的诊断与提示，一条都不能漏。\n"
                + "正常项、正常声明、常规建议不要收进来。\n"
                + "只输出那一个 JSON 对象。";

        ProbeModelCall.Result result = new ProbeModelCall(baseUrl, model, apiKey, outputDir)
                .call(prompt, pageImageList, userText, ProbeModelCall.intSetting("probe.maxTokens", 32768));
        org.assertj.core.api.Assertions.assertThat(result.httpStatus)
                .as("gateway HTTP status; response body saved under " + outputDir).isEqualTo(200);

        // 复用生产解析：finish_reason 非 stop 判死、content / reasoning_content 双通道同一套裁决。
        String content = new OpenAiCompatibleHealthReportAnalysisModelClient(objectMapper, properties(baseUrl, model, apiKey))
                .extractContent(result.envelope, "PROBE");
        JsonNode root = objectMapper.readTree(content);
        Files.write(outputDir.resolve("problems.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));

        validateAgainstSchema(root, outputDir);
        validateShape(root, pageImageList.size());
        List<Problem> extracted = readProblems(root);
        Files.write(outputDir.resolve("problems.tsv"), toTsv(extracted).getBytes(StandardCharsets.UTF_8));

        String report = buildRecallReport(extracted, pageImageList.size());
        Files.write(outputDir.resolve("recall-report.txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
        System.out.println("[probe] extracted list written to "
                + outputDir.resolve("problems.tsv").toAbsolutePath());
        softly.assertAll();
    }

    // ---------- 校验 ----------

    private void validateAgainstSchema(JsonNode root, Path outputDir) throws IOException {
        Path schemaPath = Paths.get(ProbeModelCall.setting("probe.schema", "PROBE_SCHEMA", SCHEMA_DEFAULT));
        org.assertj.core.api.Assertions.assertThat(schemaPath).as("schema file must exist").isRegularFile();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(objectMapper.readTree(Files.readAllBytes(schemaPath)));
        List<String> paths = new ArrayList<String>();
        for (ValidationMessage message : schema.validate(root)) {
            // 【只记路径与关键字，不记 ValidationMessage 正文】它会带上实际取值，那是健康数据。
            paths.add(message.getPath() + " (" + message.getType() + ")");
        }
        softly.assertThat(paths).as("output must satisfy " + schemaPath).isEmpty();
    }

    private void validateShape(JsonNode root, int pageCount) {
        softly.assertThat(fieldNames(root)).as("top-level fields must match exactly").isEqualTo(TOP_FIELDS);
        softly.assertThat(root.path("reportStatus").asText()).as("reportStatus").isEqualTo("OK");

        JsonNode problems = root.path("problems");
        boolean summarySeen = false;
        for (int index = 0; index < problems.size(); index++) {
            JsonNode item = problems.get(index);
            String label = "problems[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(ITEM_FIELDS);
            String sourceType = item.path("sourceType").asText("");
            softly.assertThat(sourceType).as(label + ".sourceType").isIn(SOURCE_TYPES.toArray());

            int page = item.path("page").asInt(-1);
            softly.assertThat(page >= 1 && page <= pageCount)
                    .as(label + ".page=" + page + " must fall within 1.." + pageCount).isTrue();

            String name = item.path("name").asText("");
            String rawText = item.path("rawText").asText("");
            softly.assertThat(name.isEmpty()).as(label + ".name must not be empty").isFalse();
            softly.assertThat(rawText.isEmpty()).as(label + ".rawText must not be empty").isFalse();

            // 需求 §6-3：问题名称直接引用报告原文表述，不做改写。
            softly.assertThat(quotedFrom(rawText, name))
                    .as(label + ".name=[" + name + "] is not quoted from its own rawText;"
                            + " requirement 6-3 forbids rewording the report")
                    .isTrue();

            // 需求 §6-1 只汇总异常结论与健康提示；§6-5 为「全部正常」另有空态文案。
            softly.assertThat(NORMAL_STATEMENTS.contains(key(name)))
                    .as(label + ".name=[" + name + "] is a normal statement, not a health problem")
                    .isFalse();

            if ("INDICATOR".equals(sourceType)) {
                // 需求 §6-3：指标异常类要能跳转到对应的指标卡片，没有指标名就跳不了。
                softly.assertThat(item.path("indicatorName").isTextual())
                        .as(label + " INDICATOR needs indicatorName for the jump button").isTrue();
                softly.assertThat(item.path("section").isTextual())
                        .as(label + " INDICATOR needs the section it belongs to").isTrue();
                // 需求 §6-4：指标异常类全部排在总检结论类之前。
                softly.assertThat(summarySeen)
                        .as(label + " is INDICATOR but appears after a SUMMARY item;"
                                + " requirement 6-4 puts all indicator problems first")
                        .isFalse();
            } else {
                summarySeen = true;
            }
        }
    }

    // ---------- 召回率 ----------

    private String buildRecallReport(List<Problem> extracted, int pageCount) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("[probe] health problem recall report\n");
        builder.append("  pages sent           ").append(pageCount).append('\n');
        builder.append("  extracted            ").append(extracted.size()).append(" problems\n");
        builder.append("  by source            ").append(bySourceType(extracted)).append('\n');
        builder.append("  per page             ").append(perPageCounts(extracted)).append('\n');
        builder.append("  with jump target     ").append(countLinked(extracted))
                .append("   (indicatorName present -> the card can be jumped to)\n");
        builder.append("  source labels        ").append(sourceLabelPreview(extracted)).append('\n');
        if (extracted.isEmpty()) {
            builder.append("\n  NOTE: no problems at all. Legitimate only if every conclusion in the\n");
            builder.append("        report is normal (requirement 6-5 empty state) -- verify by hand.\n");
        }

        String truthPath = ProbeModelCall.setting("probe.truth", "PROBE_TRUTH", "");
        if (truthPath.isEmpty()) {
            builder.append("\n  NO TRUTH FILE (-Dprobe.truth) -> recall not computed.\n");
            builder.append("  Fix problems.tsv against the paper report and pass it back as the truth file.\n");
            return builder.toString();
        }

        List<Problem> expected = readTruth(Paths.get(truthPath));
        softly.assertThat(expected.size()).as("truth file must not be empty").isGreaterThan(0);
        List<Problem> pool = new ArrayList<Problem>(extracted);
        List<Problem> matched = new ArrayList<Problem>();
        List<Problem> reworded = new ArrayList<Problem>();
        List<Problem> missing = new ArrayList<Problem>();
        for (Problem truth : expected) {
            Problem exact = takeByName(pool, truth.name);
            if (exact != null) {
                matched.add(truth);
                continue;
            }
            // 名字对不上，但某条的原文里含真值名称 -> 看见了这条，只是改写了名称。
            Problem byRawText = takeByRawText(pool, truth.name);
            if (byRawText != null) {
                reworded.add(new Problem(byRawText.page, byRawText.sourceType, byRawText.section,
                        byRawText.itemNo, byRawText.indicatorName,
                        truth.name + " -> " + byRawText.name, byRawText.rawText));
            } else {
                missing.add(truth);
            }
        }
        double recall = (double) matched.size() / expected.size();
        double minRecall = doubleSetting("probe.minRecall", 0.9D);

        builder.append("\n  expected (truth)     ").append(expected.size()).append('\n');
        builder.append("  matched              ").append(matched.size()).append('\n');
        builder.append("  reworded             ").append(reworded.size())
                .append("   (found in rawText but the name was changed -> requirement 6-3 breach,"
                        + " not a miss)\n");
        builder.append("  missing              ").append(missing.size()).append('\n');
        builder.append("  extra                ").append(pool.size())
                .append("   (extracted but not in truth; check whether the truth file is incomplete)\n");
        builder.append(String.format("  RECALL               %.3f  (threshold %.3f)%n", recall, minRecall));
        appendList(builder, "MISSING", missing);
        appendList(builder, "REWORDED", reworded);
        appendList(builder, "EXTRA", pool);

        softly.assertThat(recall)
                .as("health problem recall must reach -Dprobe.minRecall; see recall-report.txt")
                .isGreaterThanOrEqualTo(minRecall);
        return builder.toString();
    }

    /** 命中即取走，避免同名多条被同一条真值重复命中。 */
    private Problem takeByName(List<Problem> pool, String name) {
        for (int index = 0; index < pool.size(); index++) {
            if (key(pool.get(index).name).equals(key(name))) {
                return pool.remove(index);
            }
        }
        return null;
    }

    private Problem takeByRawText(List<Problem> pool, String name) {
        for (int index = 0; index < pool.size(); index++) {
            if (containsNormalized(pool.get(index).rawText, name)) {
                return pool.remove(index);
            }
        }
        return null;
    }

    private void appendList(StringBuilder builder, String title, List<Problem> list) {
        if (list.isEmpty()) {
            return;
        }
        builder.append("\n  ").append(title).append(" (").append(list.size()).append(")\n");
        for (Problem problem : list) {
            builder.append("    ").append(problem.name).append('\t')
                    .append(problem.sourceType == null ? "" : problem.sourceType);
            if (problem.page > 0) {
                builder.append("\tp").append(problem.page);
            }
            builder.append('\n');
        }
    }

    /** 后端拼来源标注的方式，打出来便于对照需求 §6-3 的示例。 */
    private String sourceLabel(Problem problem) {
        String section = problem.section == null ? "未标注章节" : problem.section;
        if ("INDICATOR".equals(problem.sourceType)) {
            return section + "–" + (problem.indicatorName == null ? problem.name : problem.indicatorName);
        }
        return problem.itemNo == null ? section : section + "第" + problem.itemNo + "条";
    }

    private String sourceLabelPreview(List<Problem> list) {
        Set<String> labels = new LinkedHashSet<String>();
        for (Problem problem : list) {
            labels.add(sourceLabel(problem));
            if (labels.size() >= 6) {
                break;
            }
        }
        return labels.toString();
    }

    /** 比较前统一规范化并去掉全部空白：全角半角、NFKC 差异不该算成漏抽。 */
    private String key(String text) {
        return textNormalizer.normalize(text == null ? "" : text)
                .replaceAll("\\s+", "").toLowerCase();
    }

    private boolean containsNormalized(String container, String candidate) {
        return key(container).contains(key(candidate));
    }

    /**
     * 判断 {@code name} 是不是从 {@code rawText} 里引下来的。
     *
     * <p><b>必须逐段判，不能整体判。</b> 报告里没有成句表述时，问题名称是
     *「指标名 + 结论标记」两段原文拼起来的（如「甘油三酯 ↑偏高」，设计方案 §6.2），
     * 而这两段在原文行里中间隔着数值、单位、参考范围——整体子串匹配必然失败，
     * 会把完全合规的条目判成改写。</p>
     *
     * <p>逐段判仍然拦得住真正的改写：「肝脏脂肪浸润」整段都不在原文里。</p>
     */
    private boolean quotedFrom(String rawText, String name) {
        if (containsNormalized(rawText, name)) {
            return true;
        }
        String[] fragments = name.split("\\s+");
        if (fragments.length < 2) {
            return false;
        }
        for (String fragment : fragments) {
            if (fragment.trim().isEmpty()) {
                continue;
            }
            if (!containsNormalized(rawText, fragment)) {
                return false;
            }
        }
        return true;
    }

    // ---------- 读写 ----------

    private List<Problem> readProblems(JsonNode root) {
        List<Problem> list = new ArrayList<Problem>();
        for (JsonNode item : root.path("problems")) {
            list.add(new Problem(item.path("page").asInt(0), text(item, "sourceType"),
                    text(item, "section"),
                    item.path("itemNo").isInt() ? Integer.valueOf(item.path("itemNo").asInt()) : null,
                    text(item, "indicatorName"), text(item, "name"), text(item, "rawText")));
        }
        return list;
    }

    private List<Problem> readTruth(Path truthPath) throws IOException {
        org.assertj.core.api.Assertions.assertThat(truthPath).as("truth file must exist").isRegularFile();
        List<Problem> list = new ArrayList<Problem>();
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
            list.add(new Problem(0, columns.length > 1 ? columns[1].trim() : null,
                    null, null, null, name, ""));
        }
        return list;
    }

    /** 与真值文件同一种格式，改完即可回填当真值用。 */
    private String toTsv(List<Problem> list) {
        StringBuilder builder = new StringBuilder();
        builder.append("# name\tsourceType\tsourceLabel\tindicatorName\tpage\trawText\n");
        builder.append("# 前两列参与匹配（第二列可省），其余列仅供人工核对；"
                + "改完可直接作为 -Dprobe.truth 传回\n");
        for (Problem problem : list) {
            builder.append(nullToEmpty(problem.name)).append('\t')
                    .append(nullToEmpty(problem.sourceType)).append('\t')
                    .append(sourceLabel(problem)).append('\t')
                    .append(nullToEmpty(problem.indicatorName)).append('\t')
                    .append(problem.page).append('\t')
                    .append(nullToEmpty(problem.rawText).replace('\t', ' ')).append('\n');
        }
        return builder.toString();
    }

    private String bySourceType(List<Problem> list) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("INDICATOR", 0);
        counts.put("SUMMARY", 0);
        for (Problem problem : list) {
            String type = problem.sourceType == null ? "(null)" : problem.sourceType;
            Integer previous = counts.get(type);
            counts.put(type, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    private String perPageCounts(List<Problem> list) {
        TreeMap<Integer, Integer> counts = new TreeMap<Integer, Integer>();
        for (Problem problem : list) {
            Integer previous = counts.get(problem.page);
            counts.put(problem.page, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    private int countLinked(List<Problem> list) {
        int count = 0;
        for (Problem problem : list) {
            if (problem.indicatorName != null && !problem.indicatorName.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ---------- 杂项 ----------

    private static final class Problem {
        private final int page;
        private final String sourceType;
        private final String section;
        private final Integer itemNo;
        private final String indicatorName;
        private final String name;
        private final String rawText;

        private Problem(int page, String sourceType, String section, Integer itemNo,
                        String indicatorName, String name, String rawText) {
            this.page = page;
            this.sourceType = sourceType;
            this.section = section;
            this.itemNo = itemNo;
            this.indicatorName = indicatorName;
            this.name = name;
            this.rawText = rawText;
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
