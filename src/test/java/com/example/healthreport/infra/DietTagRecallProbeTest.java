package com.example.healthreport.infra;

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

/**
 * 饮食建议与菜品打标召回探针：PDF → 全页图 → LLM-A → 三类来源归一化成枚举 → 与人工真值比召回。
 *
 * <p>对应需求 §7（饮食建议，第 139~183 行）与 §8（食堂菜品推荐，第 185~214 行）。</p>
 *
 * <p><b>模块四不需要模型做任何事。</b> 需求 §8-2 的推荐／不推荐方向完全由
 *「维度 + 枚举值」确定性推出，食材清单、摄入量、烹饪方式、菜品匹配全部是后端查表。
 * 所以这里只测一件事：<b>模型能不能把报告里的三类来源归一化成我们制定好的枚举值。</b>
 * 而那张确定性的方向表正好可以反过来核验它给的 {@code direction}。</p>
 *
 * <pre>
 * export EXTRACTION_BASE_URL=https://your-gateway
 * export EXTRACTION_MODEL=your-model
 * export EXTRACTION_API_KEY=sk-xxx
 *
 * # 第一次跑：没有真值，只看模型归一化出什么
 * mvn -P image-probe test -Dtest=DietTagRecallProbeTest -Dprobe.pdf=/abs/report.pdf
 *
 * # 把 target/diet-tag-recall/diet-tags.tsv 对着报告改成真值，然后
 * mvn -P image-probe test -Dtest=DietTagRecallProbeTest \
 *     -Dprobe.pdf=/abs/report.pdf -Dprobe.truth=/abs/truth.tsv -Dprobe.minRecall=0.95
 * </pre>
 *
 * <p><b>真值文件格式</b>：每行一条，制表符分隔 {@code 枚举值<TAB>维度}；
 * 只写一列时只按枚举值匹配。{@code #} 开头是注释，空行忽略。</p>
 */
@Tag("image-probe")
class DietTagRecallProbeTest {

    private static final String PROMPT_DEFAULT = "prompt/diet-tags.md";
    private static final String SCHEMA_DEFAULT = "schema/diet_tags_probe.schema.json";
    private static final String OUTPUT_DIR_DEFAULT = "target/diet-tag-recall";
    private static final Set<String> TOP_FIELDS = fields("reportStatus", "recommend", "reject");
    private static final Set<String> ITEM_FIELDS = fields("dimension", "enumKey",
            "page", "section", "itemNo", "quote", "rawText");
    private static final Set<String> DIMENSIONS = fields("ALLERGEN", "NUTRITION", "DIET");

    /** 食入性过敏原枚举；非食物过敏原（尘螨、花粉等）本模块不收，给不出「需避免的食材」。 */
    private static final Set<String> ALLERGEN_KEYS = fields("SHRIMP_CRAB", "FISH", "MILK", "EGG",
            "PEANUT", "SOY", "WHEAT", "NUTS", "MANGO", "BEEF", "MUTTON", "MOLLUSK", "SESAME",
            "DUST_MITE", "POLLEN", "ANIMAL_DANDER", "MOLD", "COCKROACH", "OTHER");
    private static final Set<String> NUTRITION_KEYS = fields("IRON", "CALCIUM", "PROTEIN",
            "VITAMIN_D", "VITAMIN_B12", "FOLATE", "DIETARY_FIBER", "ZINC", "POTASSIUM", "OTHER");
    private static final Set<String> DIET_KEYS = fields("LOW_FAT", "LOW_SODIUM", "LOW_ADDED_SUGAR",
            "LOW_PURINE", "LOW_CHOLESTEROL", "LOW_CALORIE", "HIGH_FIBER", "LIMIT_ALCOHOL",
            "LIGHT_DIET", "OTHER");

    /**
     * 需求 §8-2 第一期能做正向推荐的饮食注意维度。
     *
     * <p>低脂、低盐、限糖、限酒取决于调味料与用油量，菜品数据给不了证据，所以只做不推荐；
     * 低嘌呤与高纤维能从菜品主料确证，才可以做推荐。</p>
     */
    private static final Set<String> DIET_RECOMMEND_KEYS = fields("LOW_PURINE", "HIGH_FIBER");

    /** 非食入性过敏原：生产契约收进 reject 仅展示，不产生食材（设计方案 §4.2、§7.2）。 */
    private static final Set<String> NON_FOOD_ALLERGENS = fields("DUST_MITE", "POLLEN",
            "ANIMAL_DANDER", "MOLD", "COCKROACH");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SoftAssertions softly = new SoftAssertions();

    @Test
    void dietTagRecallOnImageOnlyInput() throws Exception {
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
                + "把报告里三类饮食来源归一化成枚举值：阳性过敏原、建议补充的营养素、"
                + "总检结论里的饮食要求。\n只输出那一个 JSON 对象。";

        ProbeModelCall.Result result = new ProbeModelCall(baseUrl, model, apiKey, outputDir)
                .call(prompt, pageImageList, userText, ProbeModelCall.intSetting("probe.maxTokens", 32768));
        org.assertj.core.api.Assertions.assertThat(result.httpStatus)
                .as("gateway HTTP status; response body saved under " + outputDir).isEqualTo(200);

        // 复用生产解析：finish_reason 非 stop 判死、content / reasoning_content 双通道同一套裁决。
        String content = new OpenAiCompatibleHealthReportAnalysisModelClient(objectMapper, properties(baseUrl, model, apiKey))
                .extractContent(result.envelope, "PROBE");
        JsonNode root = objectMapper.readTree(content);
        Files.write(outputDir.resolve("diet-tags.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));

        validateAgainstSchema(root, outputDir);
        validateShape(root, pageImageList.size());
        List<Tag> extracted = readTags(root);
        Files.write(outputDir.resolve("diet-tags.tsv"), toTsv(extracted).getBytes(StandardCharsets.UTF_8));

        String report = buildRecallReport(extracted, pageImageList.size());
        Files.write(outputDir.resolve("recall-report.txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
        System.out.println("[probe] extracted list written to "
                + outputDir.resolve("diet-tags.tsv").toAbsolutePath());
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

        validateArray(root.path("recommend"), "recommend", pageCount);
        validateArray(root.path("reject"), "reject", pageCount);
    }

    /**
     * 校验一个方向数组。
     *
     * <p><b>条目落在哪个数组里就是它的打标方向</b>（与页面「适宜多吃／忌吃少吃」两个卡片区一致），
     * 所以「方向对不对」这件事就是「它该不该出现在这个数组里」。</p>
     */
    private void validateArray(JsonNode tags, String arrayName, int pageCount) {
        for (int index = 0; index < tags.size(); index++) {
            JsonNode item = tags.get(index);
            String label = arrayName + "[" + index + "]";
            softly.assertThat(fieldNames(item)).as(label + " fields").isEqualTo(ITEM_FIELDS);

            String dimension = item.path("dimension").asText("");
            String enumKey = item.path("enumKey").asText("");
            softly.assertThat(dimension).as(label + ".dimension").isIn(DIMENSIONS.toArray());
            softly.assertThat(keysOf(dimension).contains(enumKey))
                    .as(label + ".enumKey=" + enumKey + " does not belong to dimension " + dimension)
                    .isTrue();
            // 非食入性过敏原是合法输出，但方向恒为 reject（仅展示，不产生食材）。
            if (NON_FOOD_ALLERGENS.contains(enumKey)) {
                softly.assertThat(arrayName)
                        .as(label + ".enumKey=" + enumKey + " non-food allergen must be in reject")
                        .isEqualTo("reject");
            }

            // ★ 需求 §8-2：方向由维度与枚举确定性推出，放反会把该排除的菜推给用户。
            softly.assertThat(arrayName)
                    .as(label + " dimension=" + dimension + " enumKey=" + enumKey
                            + " is in the wrong array; requirement 8-2 fixes the direction by table")
                    .isEqualTo(expectedArray(dimension, enumKey));

            int page = item.path("page").asInt(-1);
            softly.assertThat(page >= 1 && page <= pageCount)
                    .as(label + ".page=" + page + " must fall within 1.." + pageCount).isTrue();

            // quote 与 rawText 之间不做包含性校验：模型会把「减少酒精和高果糖饮料」
            // 压缩成「减少高果糖饮料」——语义没错但不是逐字子串，卡这个只会得到假警报。
            // 代价是没有任何机制能发现编造的建议原文，只能靠人工看 diet-tags.tsv。
            softly.assertThat(item.path("quote").asText("").isEmpty())
                    .as(label + ".quote must not be empty").isFalse();
        }
    }

    /** 需求 §8-2 的方向表：这是确定性映射，不是判断题。 */
    private String expectedArray(String dimension, String enumKey) {
        if ("ALLERGEN".equals(dimension)) {
            return "reject";
        }
        if ("NUTRITION".equals(dimension)) {
            return "recommend";
        }
        return DIET_RECOMMEND_KEYS.contains(enumKey) ? "recommend" : "reject";
    }

    private Set<String> keysOf(String dimension) {
        if ("ALLERGEN".equals(dimension)) {
            return ALLERGEN_KEYS;
        }
        if ("NUTRITION".equals(dimension)) {
            return NUTRITION_KEYS;
        }
        return DIET_KEYS;
    }

    // ---------- 召回率 ----------

    private String buildRecallReport(List<Tag> extracted, int pageCount) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("[probe] diet tag recall report\n");
        builder.append("  pages sent           ").append(pageCount).append('\n');
        builder.append("  extracted            ").append(extracted.size()).append(" tags\n");
        builder.append("  by dimension         ").append(byDimension(extracted)).append('\n');
        builder.append("  by direction         ").append(byDirection(extracted)).append('\n');
        builder.append("  OTHER                ").append(countOther(extracted))
                .append("   (unmapped: check by hand whether the enum table is missing an entry)\n");
        builder.append("  source labels        ").append(sourceLabelPreview(extracted)).append('\n');
        if (extracted.isEmpty()) {
            builder.append("\n  NOTE: no diet tags at all. Legitimate only if the report mentions no\n");
            builder.append("        allergen, no supplement and no dietary advice\n");
            builder.append("        (requirement 7-4 / 8-6 empty state) -- verify by hand.\n");
        }
        appendList(builder, "EXTRACTED TAGS", extracted);

        String truthPath = ProbeModelCall.setting("probe.truth", "PROBE_TRUTH", "");
        if (truthPath.isEmpty()) {
            builder.append("\n  NO TRUTH FILE (-Dprobe.truth) -> recall not computed.\n");
            builder.append("  Fix diet-tags.tsv against the paper report and pass it back as the truth file.\n");
            return builder.toString();
        }

        List<Tag> expected = readTruth(Paths.get(truthPath));
        softly.assertThat(expected.size()).as("truth file must not be empty").isGreaterThan(0);
        List<Tag> pool = new ArrayList<Tag>(extracted);
        List<Tag> matched = new ArrayList<Tag>();
        List<Tag> wrongDimension = new ArrayList<Tag>();
        List<Tag> missing = new ArrayList<Tag>();
        for (Tag truth : expected) {
            Tag exact = take(pool, truth, true);
            if (exact != null) {
                matched.add(truth);
                continue;
            }
            Tag byKey = take(pool, truth, false);
            if (byKey != null) {
                wrongDimension.add(new Tag(byKey.page, byKey.dimension, truth.enumKey,
                        byKey.direction, byKey.section, byKey.itemNo,
                        truth.dimension + " -> " + byKey.dimension, byKey.rawText));
            } else {
                missing.add(truth);
            }
        }
        double recall = (double) matched.size() / expected.size();
        double minRecall = doubleSetting("probe.minRecall", 0.9D);

        builder.append("\n  expected (truth)     ").append(expected.size()).append('\n');
        builder.append("  matched              ").append(matched.size()).append('\n');
        builder.append("  wrong dimension      ").append(wrongDimension.size())
                .append("   (right enum, wrong source category -> the card zone would be wrong)\n");
        builder.append("  missing              ").append(missing.size()).append('\n');
        builder.append("  extra                ").append(pool.size())
                .append("   (extracted but not in truth; over-extraction or an incomplete truth file)\n");
        builder.append(String.format("  RECALL               %.3f  (threshold %.3f)%n", recall, minRecall));
        appendList(builder, "MISSING", missing);
        appendList(builder, "WRONG DIMENSION", wrongDimension);
        appendList(builder, "EXTRA", pool);

        softly.assertThat(recall)
                .as("diet tag recall must reach -Dprobe.minRecall; see recall-report.txt")
                .isGreaterThanOrEqualTo(minRecall);
        return builder.toString();
    }

    /** 命中即取走；同一个枚举可能合法地出现多次（同一条原文拆出的多条不会重复枚举）。 */
    private Tag take(List<Tag> pool, Tag truth, boolean matchDimension) {
        for (int index = 0; index < pool.size(); index++) {
            Tag candidate = pool.get(index);
            if (!candidate.enumKey.equals(truth.enumKey)) {
                continue;
            }
            if (matchDimension && truth.dimension != null && !truth.dimension.isEmpty()
                    && !candidate.dimension.equals(truth.dimension)) {
                continue;
            }
            return pool.remove(index);
        }
        return null;
    }

    private void appendList(StringBuilder builder, String title, List<Tag> list) {
        if (list.isEmpty()) {
            return;
        }
        builder.append("\n  ").append(title).append(" (").append(list.size()).append(")\n");
        for (Tag tag : list) {
            builder.append("    ").append(tag.enumKey).append('\t')
                    .append(nullToEmpty(tag.dimension)).append('\t')
                    .append(nullToEmpty(tag.direction)).append('\t')
                    .append(nullToEmpty(tag.quote));
            if (tag.page > 0) {
                builder.append("\tp").append(tag.page);
            }
            builder.append('\n');
        }
    }

    /** 需求 §7-3 的来源标注，后端按 section 与 itemNo 拼；打出来便于对照需求示例。 */
    private String sourceLabel(Tag tag) {
        String section = tag.section == null ? "未标注章节" : tag.section;
        return tag.itemNo == null ? section + "–" + tag.quote : section + "第" + tag.itemNo + "条";
    }

    private String sourceLabelPreview(List<Tag> list) {
        Set<String> labels = new LinkedHashSet<String>();
        for (Tag tag : list) {
            labels.add(sourceLabel(tag));
            if (labels.size() >= 5) {
                break;
            }
        }
        return labels.toString();
    }

    // ---------- 读写 ----------

    /** 摊平两个方向数组；方向从数组名带下来，后续统计与匹配都按条目算。 */
    private List<Tag> readTags(JsonNode root) {
        List<Tag> list = new ArrayList<Tag>();
        readInto(list, root.path("recommend"), "RECOMMEND");
        readInto(list, root.path("reject"), "REJECT");
        return list;
    }

    private void readInto(List<Tag> list, JsonNode tags, String direction) {
        for (JsonNode item : tags) {
            list.add(new Tag(item.path("page").asInt(0), text(item, "dimension"),
                    item.path("enumKey").asText(""), direction, text(item, "section"),
                    item.path("itemNo").isInt() ? Integer.valueOf(item.path("itemNo").asInt()) : null,
                    text(item, "quote"), text(item, "rawText")));
        }
    }

    private List<Tag> readTruth(Path truthPath) throws IOException {
        org.assertj.core.api.Assertions.assertThat(truthPath).as("truth file must exist").isRegularFile();
        List<Tag> list = new ArrayList<Tag>();
        for (String line : Files.readAllLines(truthPath, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\t", -1);
            String enumKey = columns[0].trim();
            if (enumKey.isEmpty()) {
                continue;
            }
            list.add(new Tag(0, columns.length > 1 ? columns[1].trim() : null, enumKey,
                    null, null, null, null, null));
        }
        return list;
    }

    /** 与真值文件同一种格式，改完即可回填当真值用。 */
    private String toTsv(List<Tag> list) {
        StringBuilder builder = new StringBuilder();
        builder.append("# enumKey\tdimension\tdirection\tsourceLabel\tpage\tquote\n");
        builder.append("# 前两列参与匹配（第二列可省），其余列仅供人工核对；"
                + "改完可直接作为 -Dprobe.truth 传回\n");
        for (Tag tag : list) {
            builder.append(tag.enumKey).append('\t')
                    .append(nullToEmpty(tag.dimension)).append('\t')
                    .append(nullToEmpty(tag.direction)).append('\t')
                    .append(sourceLabel(tag)).append('\t')
                    .append(tag.page).append('\t')
                    .append(nullToEmpty(tag.quote).replace('\t', ' ')).append('\n');
        }
        return builder.toString();
    }

    private String byDimension(List<Tag> list) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("ALLERGEN", 0);
        counts.put("NUTRITION", 0);
        counts.put("DIET", 0);
        for (Tag tag : list) {
            String dimension = tag.dimension == null ? "(null)" : tag.dimension;
            Integer previous = counts.get(dimension);
            counts.put(dimension, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    private String byDirection(List<Tag> list) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("RECOMMEND", 0);
        counts.put("REJECT", 0);
        for (Tag tag : list) {
            String direction = tag.direction == null ? "(null)" : tag.direction;
            Integer previous = counts.get(direction);
            counts.put(direction, previous == null ? 1 : previous + 1);
        }
        return counts.toString();
    }

    private int countOther(List<Tag> list) {
        int count = 0;
        for (Tag tag : list) {
            if ("OTHER".equals(tag.enumKey)) {
                count++;
            }
        }
        return count;
    }

    // ---------- 杂项 ----------

    private static final class Tag {
        private final int page;
        private final String dimension;
        private final String enumKey;
        private final String direction;
        private final String section;
        private final Integer itemNo;
        private final String quote;
        private final String rawText;

        private Tag(int page, String dimension, String enumKey, String direction, String section,
                    Integer itemNo, String quote, String rawText) {
            this.page = page;
            this.dimension = dimension;
            this.enumKey = enumKey;
            this.direction = direction;
            this.section = section;
            this.itemNo = itemNo;
            this.quote = quote;
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
