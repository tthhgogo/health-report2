package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import com.example.healthreport.llm.schema.ModelOutputSchema;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** LLM-B Schema、批次覆盖和互斥的唯一校验入口。 */
@Slf4j
@Component
public class DishTagContractValidator {

    /** 单批可归入 UNKNOWN 的菜品占比上限；超过说明这一批整体跑偏。 */
    private static final double MAX_REPAIR_RATIO = 0.20D;

    /** 无论占比如何都允许修复的菜品数下限，避免小批次修一道就作废。 */
    private static final int MIN_ALLOWED_REPAIRS = 1;

    /** 两个 id 数组的路径；它们的 uniqueItems 违规按「去重」修复。 */
    private static final Set<String> ID_ARRAY_PATH_SET = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("$.neutralDishIds", "$.unknownDishIds")));

    /** networknt 对 uniqueItems 违规使用的关键字。 */
    private static final String UNIQUE_ITEMS_KEYWORD = "uniqueItems";

    /** Schema 违规路径形如 {@code $.hitList[3].evidenceType}。 */
    private static final Pattern HIT_PATH_PATTERN = Pattern.compile("^\\$\\.hitList\\[(\\d+)\\]");

    private final ObjectMapper objectMapper;

    /**
     * LLM-B 输出契约。
     * <p>校验一律走<b>完整版</b>——传输版剥掉的两条 evidenceType 条件约束
     * （{@code B_INGREDIENT_EVIDENCE}、{@code B_REASON_EVIDENCE}）只有在这里才拦得住。</p>
     */
    private final ModelOutputSchema modelOutputSchema;

    public DishTagContractValidator(ObjectMapper objectMapper,
                                    ModelOutputSchemaRegistry schemaRegistry) {
        if (objectMapper == null || schemaRegistry == null) {
            throw new IllegalArgumentException("JSON处理器与Schema注册表不能为空");
        }
        this.objectMapper = objectMapper;
        this.modelOutputSchema = schemaRegistry.dishTag();
    }

    /**
     * 校验并反序列化模型响应；少、多、重复或跨列表相交均整批拒绝。
     */
    public DishTagOutput validate(String responseJson, String expectedEnumKey,
                                  List<Dish> inputDishList) {
        // 【输入侧检查必须在 try 之外】它查的是调用方传进来的批次，不是模型返回值。
        // 放在 try 里会被下面的 RuntimeException 兜底转成「模型批次被拒」，
        // 结果一样但把编排层的 bug 伪装成模型问题，排障时找错方向。
        Set<Long> expectedDishIdSet = collectInputDishIds(inputDishList);
        ValidationStage validationStage = ValidationStage.JSON_PARSE;
        Set<ValidationMessage> schemaViolationSet = Collections.emptySet();
        // 【必须并入同一预算】Schema 阶段无限量剔 hit、覆盖阶段再单独算预算的话，
        // 「几十条不属于本批且格式全坏的 hit」会被先删光，剩下的恰好覆盖完整、零问题通过。
        // 记的是 dishId 而不是条数：属于本批的那些剔掉后会变成「缺失」被重复计一次。
        Set<Long> schemaDroppedDishIdSet = Collections.emptySet();
        int unattributableDropCount = 0;
        try {
            String normalizedResponseJson = unwrapMarkdownJsonFence(responseJson);
            // 尾随内容必须拒绝：围栏剥离只处理整体包裹，「JSON 后面又写了几句」要靠这个拦。
            JsonNode rootNode = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(normalizedResponseJson);
            if (rootNode == null) {
                throw rejected();
            }
            validationStage = ValidationStage.SCHEMA;
            schemaViolationSet = modelOutputSchema.validate(rootNode);
            if (!schemaViolationSet.isEmpty()) {
                SchemaRepair schemaRepair = repairSchemaViolations(rootNode, schemaViolationSet,
                        expectedEnumKey);
                // 违规既不在 hitList 条目上、也不是 id 数组的重复：定位不到「哪道菜」，修复无从下手。
                if (schemaRepair == null) {
                    throw rejected();
                }
                rootNode = schemaRepair.rootNode;
                schemaDroppedDishIdSet = schemaRepair.droppedHitDishIdSet;
                unattributableDropCount = schemaRepair.unattributableDropCount;
                schemaViolationSet = modelOutputSchema.validate(rootNode);
                if (!schemaViolationSet.isEmpty()) {
                    throw rejected();
                }
            }
            validationStage = ValidationStage.DESERIALIZATION;
            DishTagOutput output = objectMapper.treeToValue(rootNode, DishTagOutput.class);
            validationStage = ValidationStage.COVERAGE;
            repairCoverage(output, expectedEnumKey, expectedDishIdSet, schemaDroppedDishIdSet,
                    unattributableDropCount);
            return output;
        } catch (DishTagBatchRejectedException exception) {
            logRejected(validationStage, expectedEnumKey, expectedDishIdSet.size(), schemaViolationSet,
                    responseJson, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("LLM-B批次契约处理异常，阶段={}，enumKey={}，批次菜品数={}，异常类型={}",
                    validationStage.getDescription(), expectedEnumKey, expectedDishIdSet.size(),
                    exception.getClass().getName(), exception);
            logResponseDebug(validationStage, schemaViolationSet, responseJson);
            throw rejected();
        }
    }

    /**
     * 兼容模型把完整 JSON 包在 Markdown 代码围栏中的常见输出。
     * 只接受整个响应由 {@code ```json}（或无语言标签的 {@code ```}）完整包裹，
     * 不从解释性文本中搜索 JSON，避免把混合输出静默纠正为合法响应。
     */
    private String unwrapMarkdownJsonFence(String responseJson) {
        if (responseJson == null) {
            return null;
        }
        String trimmedResponse = responseJson.trim();
        if (!trimmedResponse.startsWith("```")) {
            return trimmedResponse;
        }
        int openingLineEnd = trimmedResponse.indexOf('\n');
        if (openingLineEnd < 0) {
            return trimmedResponse;
        }
        String openingFence = trimmedResponse.substring(0, openingLineEnd).trim();
        if (!("```json".equalsIgnoreCase(openingFence) || "```".equals(openingFence))) {
            return trimmedResponse;
        }
        int closingLineStart = trimmedResponse.lastIndexOf('\n');
        if (closingLineStart <= openingLineEnd
                || !"```".equals(trimmedResponse.substring(closingLineStart + 1).trim())) {
            return trimmedResponse;
        }
        return trimmedResponse.substring(openingLineEnd + 1, closingLineStart).trim();
    }

    /** 记录模型契约拒绝；普通日志只写安全坐标，完整违规详情与响应仅进 DEBUG。 */
    private void logRejected(ValidationStage validationStage, String expectedEnumKey, int dishCount,
                             Set<ValidationMessage> schemaViolationSet, String responseJson,
                             DishTagBatchRejectedException exception) {
        log.warn("LLM-B批次契约校验拒绝，阶段={}，enumKey={}，批次菜品数={}，Schema违规数={}",
                validationStage.getDescription(), expectedEnumKey, dishCount, schemaViolationSet.size(), exception);
        logResponseDebug(validationStage, schemaViolationSet, responseJson);
    }

    /** LLM-B 只处理公开菜品数据，允许在本类 DEBUG 日志中记录完整校验详情与响应。 */
    private void logResponseDebug(ValidationStage validationStage,
                                  Set<ValidationMessage> schemaViolationSet, String responseJson) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (!schemaViolationSet.isEmpty()) {
            log.debug("LLM-B Schema违规详情：{}", schemaViolationSet);
        }
        log.debug("LLM-B契约校验失败响应正文，阶段={}：{}", validationStage.getDescription(), responseJson);
    }

    /**
     * 汇总本批输入的菜品 ID，重复即编排层 bug。
     * 这里抛 IllegalArgumentException 而不是「批次被拒」——两者结果都是整批作废，
     * 但前者指向调用方，后者指向模型，混同会掩盖真正的缺陷。
     */
    private Set<Long> collectInputDishIds(List<Dish> inputDishList) {
        Set<Long> expectedDishIdSet = new HashSet<Long>(inputDishList.size());
        for (Dish dish : inputDishList) {
            if (!expectedDishIdSet.add(dish.getDishId())) {
                throw new IllegalArgumentException("批次输入菜品ID重复");
            }
        }
        return expectedDishIdSet;
    }

    /**
     * 修复可定位的 Schema 违规。
     *
     * <p>两类可修：{@code hitList} 某条坏了就剔除该条（菜品随后由覆盖修复归入 UNKNOWN）；
     * 两个 id 数组的 {@code uniqueItems} 违规直接去重——<b>列表内重复的意图是明确的</b>，
     * 模型只是把同一道菜写了两遍，去重是忠实修复，不改变它的判定。
     * 跨列表重复是另一回事（意图自相矛盾），由 {@link #repairCoverage} 归入 UNKNOWN。</p>
     *
     * @return 修复后的节点与剔除条数；有无法定位的违规时返回 {@code null}
     */
    private SchemaRepair repairSchemaViolations(JsonNode rootNode, Set<ValidationMessage> violationSet,
                                                String expectedEnumKey) {
        Set<Integer> droppedHitIndexSet = new TreeSet<Integer>();
        Set<String> deduplicatedArraySet = new TreeSet<String>();
        for (ValidationMessage violation : violationSet) {
            Matcher matcher = HIT_PATH_PATTERN.matcher(violation.getPath());
            if (matcher.find()) {
                droppedHitIndexSet.add(Integer.valueOf(matcher.group(1)));
                continue;
            }
            if (UNIQUE_ITEMS_KEYWORD.equals(violation.getType())
                    && ID_ARRAY_PATH_SET.contains(violation.getPath())) {
                deduplicatedArraySet.add(violation.getPath().substring("$.".length()));
                continue;
            }
            log.warn("LLM-B Schema 违规无法定位到某道菜，整批作废，enumKey={}，关键字={}，路径={}",
                    expectedEnumKey, violation.getType(), violation.getPath());
            return null;
        }

        ObjectNode repairedNode = (ObjectNode) rootNode.deepCopy();
        Set<Long> droppedHitDishIdSet = new TreeSet<Long>();
        int[] unattributableCount = new int[1];
        collectHitDishIds(repairedNode, droppedHitIndexSet, droppedHitDishIdSet, unattributableCount);
        pruneHits(repairedNode, droppedHitIndexSet);
        for (String arrayField : deduplicatedArraySet) {
            deduplicateIdArray(repairedNode, arrayField);
        }
        log.warn("LLM-B Schema 违规已修复，enumKey={}，剔除hit数={}（其中归不到dishId={}），去重数组={}",
                expectedEnumKey, droppedHitIndexSet.size(), unattributableCount[0],
                deduplicatedArraySet);
        return new SchemaRepair(repairedNode, droppedHitDishIdSet, unattributableCount[0]);
    }

    /** Schema 阶段的修复结果；被剔 hit 的 dishId 要并入覆盖阶段的同一预算。 */
    private static final class SchemaRepair {

        private final JsonNode rootNode;

        private final Set<Long> droppedHitDishIdSet;

        /** 被剔但归不到任何 dishId 的条数；覆盖阶段看不见它们，必须单独计入预算。 */
        private final int unattributableDropCount;

        private SchemaRepair(JsonNode rootNode, Set<Long> droppedHitDishIdSet,
                             int unattributableDropCount) {
            this.rootNode = rootNode;
            this.droppedHitDishIdSet = droppedHitDishIdSet;
            this.unattributableDropCount = unattributableDropCount;
        }
    }

    /**
     * 剔除前先记下这些 hit 对应的 dishId；剔完就查不到了。
     *
     * <p><b>归不到 dishId 的也要计数。</b> {@code dishId} 缺失或给成字符串的坏 hit，
     * 剔掉之后既不在 {@code expectedDishIdSet} 里、也不会变成「缺失」，
     * 覆盖阶段完全看不见它——不单独计数的话，几十条这种垃圾 hit 会以「零问题」通过。</p>
     */
    private void collectHitDishIds(ObjectNode rootNode, Set<Integer> hitIndexSet,
                                   Set<Long> attributedDishIdSet, int[] unattributableCount) {
        JsonNode hitListNode = rootNode.get("hitList");
        if (!(hitListNode instanceof ArrayNode)) {
            unattributableCount[0] += hitIndexSet.size();
            return;
        }
        for (Integer index : hitIndexSet) {
            JsonNode hitNode = hitListNode.get(index.intValue());
            if (hitNode != null && hitNode.path("dishId").isIntegralNumber()) {
                attributedDishIdSet.add(Long.valueOf(hitNode.path("dishId").asLong()));
            } else {
                unattributableCount[0]++;
            }
        }
    }

    /** 按下标倒序移除 hit，避免前面的删除影响后面的下标。 */
    private void pruneHits(ObjectNode rootNode, Set<Integer> droppedHitIndexSet) {
        JsonNode hitListNode = rootNode.get("hitList");
        if (droppedHitIndexSet.isEmpty() || !(hitListNode instanceof ArrayNode)) {
            return;
        }
        ArrayNode hitArrayNode = (ArrayNode) hitListNode;
        List<Integer> descendingIndexList = new ArrayList<Integer>(droppedHitIndexSet);
        Collections.reverse(descendingIndexList);
        for (Integer index : descendingIndexList) {
            if (index.intValue() < hitArrayNode.size()) {
                hitArrayNode.remove(index.intValue());
            }
        }
    }

    /** 保留首次出现，去掉后续重复；不改变任何一道菜的归属。 */
    private void deduplicateIdArray(ObjectNode rootNode, String arrayField) {
        JsonNode arrayNode = rootNode.get(arrayField);
        if (!(arrayNode instanceof ArrayNode)) {
            return;
        }
        ArrayNode sourceArrayNode = (ArrayNode) arrayNode;
        ArrayNode targetArrayNode = rootNode.putArray(arrayField);
        Set<Long> seenDishIdSet = new HashSet<Long>();
        for (JsonNode elementNode : sourceArrayNode) {
            if (!elementNode.isIntegralNumber() || seenDishIdSet.add(elementNode.asLong())) {
                targetArrayNode.add(elementNode);
            }
        }
    }

    /**
     * 修复覆盖与互斥：有问题的 {@code dishId} 一律归入 {@code unknownDishIds}，其余保持原判。
     *
     * <p><b>为什么是 UNKNOWN 而不是丢弃。</b> 三集合的并集必须精确等于本批全部 {@code dishId}，
     * 丢掉一道菜就没有归属、覆盖立刻不成立。而 {@code UNKNOWN} 是<b>安全侧</b>：
     * 任一可拒绝维度为 {@code UNKNOWN} 的菜不进任何推荐 staging SET，不会被推荐出去。</p>
     *
     * <p><b>2026-09-02 由「整批作废」改为「归入 UNKNOWN」。</b> 实测真实菜单会出现重复标签与
     * 缺失标签；整批作废下任一批出问题就导致该企业当天 33 个集合一个都不发布，
     * 失败率随批数指数放大（一家 1000 道菜的企业是 25 批）。原 §8.2
     * 「遗漏的菜绝不静默补成 UNKNOWN」的顾虑由两点承接：<b>归入必打日志</b>（LLM-B 是全案唯一
     * 允许记录完整请求响应的链路），以及<b>归入量超过 20% 即整批作废</b>——大比例出问题
     * 说明这一批整体跑偏，不是个别抖动。</p>
     */
    private void repairCoverage(DishTagOutput output, String expectedEnumKey,
                                Set<Long> expectedDishIdSet, Set<Long> schemaDroppedDishIdSet,
                                int unattributableDropCount) {
        if (output == null || !expectedEnumKey.equals(output.getEnumKey())) {
            throw rejected();
        }

        // 【先做列表内去重，再统计跨列表相交】——同一列表里重复只是抄重了，意图明确，
        // 去重是忠实修复；跨列表相交才是两个判定打架，那种才归 UNKNOWN。
        // hitList 的 verdict 只有 REJECT 一个取值，同一道菜出现两次结论必然一致。
        int deduplicatedHitCount = deduplicateHitList(output);

        Map<Long, Integer> occurrenceByDishId = new LinkedHashMap<Long, Integer>();
        countDistinct(occurrenceByDishId, output.getNeutralDishIds());
        countDistinct(occurrenceByDishId, output.getUnknownDishIds());
        for (DishTagOutput.Hit hit : output.getHitList()) {
            countOccurrence(occurrenceByDishId, hit == null ? null : hit.getDishId());
        }

        // 三类问题菜：重复出现的、本批没有的、模型压根没提的。
        Set<Long> duplicatedDishIdSet = new TreeSet<Long>();
        Set<Long> unexpectedDishIdSet = new TreeSet<Long>();
        for (Map.Entry<Long, Integer> entry : occurrenceByDishId.entrySet()) {
            if (!expectedDishIdSet.contains(entry.getKey())) {
                unexpectedDishIdSet.add(entry.getKey());
            } else if (entry.getValue().intValue() > 1) {
                duplicatedDishIdSet.add(entry.getKey());
            }
        }
        Set<Long> missingDishIdSet = new TreeSet<Long>(expectedDishIdSet);
        missingDishIdSet.removeAll(occurrenceByDishId.keySet());

        Set<Long> repairedDishIdSet = new TreeSet<Long>(duplicatedDishIdSet);
        repairedDishIdSet.addAll(missingDishIdSet);
        // 被剔 hit 里属于本批的那些已经以「缺失」的身份进了 repairedDishIdSet，不再重复计；
        // 不属于本批的那些剔完就消失了，覆盖阶段看不见，必须在这里单独计上。
        Set<Long> droppedOutsideBatchSet = new TreeSet<Long>(schemaDroppedDishIdSet);
        droppedOutsideBatchSet.removeAll(expectedDishIdSet);
        int problemTotal = repairedDishIdSet.size() + unexpectedDishIdSet.size()
                + droppedOutsideBatchSet.size() + unattributableDropCount;
        if (problemTotal == 0) {
            if (deduplicatedHitCount > 0) {
                log.warn("LLM-B hitList 列表内重复已去重（不改判），enumKey={}，去掉条数={}",
                        expectedEnumKey, deduplicatedHitCount);
            }
            // 零问题时覆盖本应平凡成立，仍然显式验一次：这条断言防的是上面那套计数逻辑
            // 将来被改错——那种错误恰好会表现为「算出零问题」，静默放行。
            assertCoverage(output, expectedDishIdSet);
            return;
        }

        int allowed = Math.max(MIN_ALLOWED_REPAIRS,
                (int) (expectedDishIdSet.size() * MAX_REPAIR_RATIO));
        if (problemTotal > allowed) {
            log.warn("LLM-B 修复量超过预算，整批作废，enumKey={}，批次菜品数={}，"
                            + "Schema剔除非本批hit={}，归不到dishId={}，重复={}，缺失={}，多余={}，"
                            + "合计={}，上限={}",
                    expectedEnumKey, expectedDishIdSet.size(), droppedOutsideBatchSet.size(),
                    unattributableDropCount,
                    duplicatedDishIdSet.size(), missingDishIdSet.size(),
                    unexpectedDishIdSet.size(), problemTotal, allowed);
            throw rejected();
        }

        rebuildBySet(output, expectedDishIdSet, repairedDishIdSet);
        // LLM-B 只处理公开菜品数据，dishId 可进普通日志（§13.2.7 全案唯一例外）。
        log.warn("LLM-B 覆盖修复，enumKey={}，批次菜品数={}，归入UNKNOWN={}（重复={}，缺失={}），"
                        + "丢弃非本批dishId={}",
                expectedEnumKey, expectedDishIdSet.size(), repairedDishIdSet,
                duplicatedDishIdSet, missingDishIdSet, unexpectedDishIdSet);
        if (deduplicatedHitCount > 0) {
            log.warn("LLM-B hitList 列表内重复已去重（不改判），enumKey={}，去掉条数={}",
                    expectedEnumKey, deduplicatedHitCount);
        }

        assertCoverage(output, expectedDishIdSet);
    }

    /**
     * 按「每个 expected dishId 恰好去一个集合」重建，覆盖与互斥由构造保证。
     * <p>问题菜一律进 {@code unknownDishIds}；不在本批的 {@code dishId} 直接消失。</p>
     */
    private void rebuildBySet(DishTagOutput output, Set<Long> expectedDishIdSet,
                              Set<Long> repairedDishIdSet) {
        List<DishTagOutput.Hit> keptHitList = new ArrayList<DishTagOutput.Hit>();
        Set<Long> placedDishIdSet = new HashSet<Long>();
        for (DishTagOutput.Hit hit : output.getHitList()) {
            if (hit == null || hit.getDishId() == null) {
                continue;
            }
            if (keepable(hit.getDishId(), expectedDishIdSet, repairedDishIdSet, placedDishIdSet)) {
                keptHitList.add(hit);
            }
        }
        List<Long> keptNeutralList = new ArrayList<Long>();
        for (Long dishId : output.getNeutralDishIds()) {
            if (keepable(dishId, expectedDishIdSet, repairedDishIdSet, placedDishIdSet)) {
                keptNeutralList.add(dishId);
            }
        }
        List<Long> keptUnknownList = new ArrayList<Long>();
        for (Long dishId : output.getUnknownDishIds()) {
            if (keepable(dishId, expectedDishIdSet, repairedDishIdSet, placedDishIdSet)) {
                keptUnknownList.add(dishId);
            }
        }
        keptUnknownList.addAll(repairedDishIdSet);

        output.setHitList(keptHitList);
        output.setNeutralDishIds(keptNeutralList);
        output.setUnknownDishIds(keptUnknownList);
    }

    private boolean keepable(Long dishId, Set<Long> expectedDishIdSet, Set<Long> repairedDishIdSet,
                             Set<Long> placedDishIdSet) {
        return dishId != null && expectedDishIdSet.contains(dishId)
                && !repairedDishIdSet.contains(dishId) && placedDishIdSet.add(dishId);
    }

    /** 修复后的兜底断言：构造保证的性质仍然显式验一次，不合就整批作废。 */
    private void assertCoverage(DishTagOutput output, Set<Long> expectedDishIdSet) {
        Set<Long> actualDishIdSet = new HashSet<Long>(expectedDishIdSet.size());
        for (Long dishId : output.getNeutralDishIds()) {
            if (!actualDishIdSet.add(dishId)) {
                throw rejected();
            }
        }
        for (Long dishId : output.getUnknownDishIds()) {
            if (!actualDishIdSet.add(dishId)) {
                throw rejected();
            }
        }
        for (DishTagOutput.Hit hit : output.getHitList()) {
            if (hit == null || hit.getDishId() == null || !actualDishIdSet.add(hit.getDishId())) {
                throw rejected();
            }
        }
        if (!actualDishIdSet.equals(expectedDishIdSet)) {
            throw rejected();
        }
    }

    /** 同一列表里的重复保留首次出现；返回被去掉的 hit 条数，仅用于日志。 */
    private int deduplicateHitList(DishTagOutput output) {
        List<DishTagOutput.Hit> deduplicatedList = new ArrayList<DishTagOutput.Hit>();
        Set<Long> seenDishIdSet = new HashSet<Long>();
        int removedCount = 0;
        for (DishTagOutput.Hit hit : output.getHitList()) {
            if (hit != null && hit.getDishId() != null && !seenDishIdSet.add(hit.getDishId())) {
                removedCount++;
                continue;
            }
            deduplicatedList.add(hit);
        }
        if (removedCount > 0) {
            output.setHitList(deduplicatedList);
        }
        return removedCount;
    }

    /** 同一列表里的重复只计一次；跨列表的重复才会让计数超过 1。 */
    private void countDistinct(Map<Long, Integer> occurrenceByDishId, List<Long> dishIdList) {
        Set<Long> distinctDishIdSet = new HashSet<Long>(dishIdList.size());
        for (Long dishId : dishIdList) {
            if (dishId != null && distinctDishIdSet.add(dishId)) {
                countOccurrence(occurrenceByDishId, dishId);
            }
        }
    }

    private void countOccurrence(Map<Long, Integer> occurrenceByDishId, Long dishId) {
        if (dishId == null) {
            return;
        }
        Integer previous = occurrenceByDishId.get(dishId);
        occurrenceByDishId.put(dishId, previous == null ? 1 : previous.intValue() + 1);
    }

    private DishTagBatchRejectedException rejected() {
        // 不把 Schema 消息或模型正文放进异常，避免被上层日志意外输出。
        return new DishTagBatchRejectedException("LLM-B批次契约校验失败");
    }

    /** LLM-B 响应在契约处理链路中的失败阶段。 */
    private enum ValidationStage {

        /** Jackson 尚未把响应正文解析为 JSON 树。 */
        JSON_PARSE("JSON解析"),

        /** JSON 树未通过正式输出 Schema。 */
        SCHEMA("Schema校验"),

        /** 合法 JSON 树无法转换为紧凑响应 DTO。 */
        DESERIALIZATION("DTO反序列化"),

        /** DTO 未通过批次覆盖、互斥或枚举一致性校验。 */
        COVERAGE("覆盖互斥校验");

        private final String description;

        ValidationStage(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
