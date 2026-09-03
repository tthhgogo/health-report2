package com.example.healthreport.llm.extraction;

import com.example.healthreport.llm.schema.ModelOutputSchema;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 在任何业务处理前执行 LLM-A Schema 校验，并把可定位到单个条目的违规<b>剔除</b>而不是整批作废。
 *
 * <p><b>为什么不再一票否决。</b> 实测单条目不合 Schema 的概率约 1.2%，而整批作废模式下
 * 一批的通过率是 {@code (1-p)^条目数}——一份 24 页报告约 200 条，整任务成功率只有 8%。
 * 失败率被条目数指数放大，靠优化提示词压不住这个乘方（2026-09-02 实测，§4.4-①）。</p>
 *
 * <p><b>什么不剔除。</b> {@code allergens} 是一级红线，静默少一条等于把格式错误伪装成漏抽，
 * 还会污染 {@code ALLERGEN_SUSPECT_MISS} 的含义；{@code sections} 被其他条目按
 * {@code sectionIndex} 引用，剔除会破坏引用完整性。这两个数组连同顶层字段一律整批作废。</p>
 */
@Slf4j
@Component
public class ExtractionSchemaValidator {

    /** 允许按条目剔除的数组；不在表内的一律整批作废。 */
    private static final Set<String> DROPPABLE_ARRAY_SET = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("indicators", "textualFindings", "summaryConclusions",
                    "nutritionSupplements", "dietRequirements")));

    /**
     * 单批可剔除条目占比上限。
     * <p>偶发一两条是模型的正常抖动，剔掉即可；<b>大比例剔除说明这一批整体跑偏</b>，
     * 那时候放行会得到一份严重残缺却只标着 partial 的报告，不如响亮地失败。</p>
     */
    private static final double MAX_DROP_RATIO = 0.20D;

    /**
     * 无论占比如何都允许剔除的条目数下限。
     * <p>没有它，只有两三个条目的小批次（封面页、单项复查）剔一条就超 20%，
     * 整任务因此失败——那恰恰是本次改动要消除的失败形态。</p>
     */
    private static final int MIN_ALLOWED_DROPS = 1;

    /** 违规路径形如 {@code $.indicators[6].status}；没有数组下标的一律不可剔除。 */
    private static final Pattern ITEM_PATH_PATTERN =
            Pattern.compile("^\\$\\.([A-Za-z]+)\\[(\\d+)\\]");

    private final ObjectMapper objectMapper;

    private final ModelOutputSchema modelOutputSchema;

    public ExtractionSchemaValidator(ObjectMapper objectMapper,
                                     ModelOutputSchemaRegistry schemaRegistry) {
        if (objectMapper == null || schemaRegistry == null) {
            throw new IllegalArgumentException("Schema 校验依赖不能为空");
        }
        this.objectMapper = objectMapper;
        this.modelOutputSchema = schemaRegistry.extraction();
    }

    /**
     * 解析并校验模型响应。
     *
     * @param rawContent 模型返回的 JSON 正文
     * @param batchIndex 仅用于日志定位，不进入任何业务判断
     * @return 已通过完整版 Schema 的输出，以及被剔除的条目统计
     * @throws HealthReportException 违规无法通过剔除条目消除时固定映射 SERVER_ERROR，调用方不得重试
     */
    public SchemaValidationOutcome validate(String rawContent, int batchIndex) {
        JsonNode rootNode;
        try {
            // 尾随内容必须拒绝：默认配置会静默丢掉 JSON 之后的解释性文字。
            rootNode = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(rawContent);
        } catch (IOException | RuntimeException exception) {
            log.error("LLM-A Schema 校验失败，batchIndex={}，阶段=JSON解析，异常类型={}",
                    batchIndex, exception.getClass().getName());
            throw fail();
        }
        if (rootNode == null || !rootNode.isObject()) {
            log.error("LLM-A Schema 校验失败，batchIndex={}，阶段=根节点不是对象", batchIndex);
            throw fail();
        }

        Set<ValidationMessage> violationSet = validateQuietly(rootNode, batchIndex);
        if (violationSet.isEmpty()) {
            return new SchemaValidationOutcome(rootNode,
                    Collections.<String, Integer>emptyMap());
        }

        Map<String, Set<Integer>> dropIndexByArray = planDrops(violationSet, batchIndex);
        // 存在无法定位到单个条目的违规：顶层坏了，剔除救不回来。
        if (dropIndexByArray == null) {
            throw fail();
        }
        if (exceedsDropBudget(rootNode, dropIndexByArray, batchIndex)) {
            throw fail();
        }
        ObjectNode prunedNode = pruneItems((ObjectNode) rootNode, dropIndexByArray);

        Set<ValidationMessage> remainingSet = validateQuietly(prunedNode, batchIndex);
        if (!remainingSet.isEmpty()) {
            // 剔除后仍不合法：违规不在被剔除的那些条目上，或数组整体约束不满足。
            log.error("LLM-A Schema 校验失败，batchIndex={}，阶段=剔除后仍不合法，剩余违规数={}，{}",
                    batchIndex, remainingSet.size(), summarize(remainingSet));
            throw fail();
        }

        Map<String, Integer> droppedCountByArray = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, Set<Integer>> entry : dropIndexByArray.entrySet()) {
            droppedCountByArray.put(entry.getKey(), entry.getValue().size());
        }
        return new SchemaValidationOutcome(prunedNode, droppedCountByArray);
    }

    /**
     * 校验并记录违规的<b>关键字与 JSON 路径</b>。
     * <p>{@code ValidationMessage} 的正文可能带模型输出的值（患者姓名、检验结果），
     * 一个字都不记；路径形如 {@code $.indicators[3].status}，不含任何健康数据。</p>
     */
    private Set<ValidationMessage> validateQuietly(JsonNode node, int batchIndex) {
        try {
            return modelOutputSchema.validate(node);
        } catch (RuntimeException exception) {
            log.error("LLM-A Schema 校验失败，batchIndex={}，阶段=校验器异常，异常类型={}",
                    batchIndex, exception.getClass().getName());
            throw fail();
        }
    }

    /**
     * 把违规归到可剔除的条目上。
     *
     * @return 每个数组要剔除的下标；只要有一条违规无法定位到可剔除条目就返回 {@code null}
     */
    private Map<String, Set<Integer>> planDrops(Set<ValidationMessage> violationSet, int batchIndex) {
        Map<String, Set<Integer>> resultMap = new LinkedHashMap<String, Set<Integer>>();
        for (ValidationMessage violation : violationSet) {
            Matcher matcher = ITEM_PATH_PATTERN.matcher(violation.getPath());
            if (!matcher.find() || !DROPPABLE_ARRAY_SET.contains(matcher.group(1))) {
                log.error("LLM-A Schema 校验失败，batchIndex={}，阶段=违规无法定位到可剔除条目，"
                        + "关键字={}，路径={}，违规总数={}", batchIndex, violation.getType(),
                        violation.getPath(), violationSet.size());
                return null;
            }
            String arrayField = matcher.group(1);
            Set<Integer> indexSet = resultMap.get(arrayField);
            if (indexSet == null) {
                indexSet = new TreeSet<Integer>();
                resultMap.put(arrayField, indexSet);
            }
            indexSet.add(Integer.valueOf(matcher.group(2)));
        }
        log.warn("LLM-A 条目剔除，batchIndex={}，{}", batchIndex, summarize(violationSet));
        return resultMap;
    }

    /** 剔除量超过预算即认为整批不可信；分母是全部可剔除数组的条目总数。 */
    private boolean exceedsDropBudget(JsonNode rootNode, Map<String, Set<Integer>> dropIndexByArray,
                                      int batchIndex) {
        int droppableTotal = 0;
        for (String arrayField : DROPPABLE_ARRAY_SET) {
            droppableTotal += rootNode.path(arrayField).size();
        }
        int dropTotal = 0;
        for (Set<Integer> indexSet : dropIndexByArray.values()) {
            dropTotal += indexSet.size();
        }
        int allowed = Math.max(MIN_ALLOWED_DROPS, (int) (droppableTotal * MAX_DROP_RATIO));
        if (dropTotal <= allowed) {
            return false;
        }
        log.error("LLM-A Schema 校验失败，batchIndex={}，阶段=剔除量超预算，"
                        + "拟剔除={}，可剔除条目总数={}，上限={}（{}% 或至少 {} 条）",
                batchIndex, dropTotal, droppableTotal, allowed, (int) (MAX_DROP_RATIO * 100),
                MIN_ALLOWED_DROPS);
        return true;
    }

    /** 按下标倒序移除，避免前面的删除影响后面的下标。 */
    private ObjectNode pruneItems(ObjectNode rootNode, Map<String, Set<Integer>> dropIndexByArray) {
        ObjectNode prunedNode = rootNode.deepCopy();
        for (Map.Entry<String, Set<Integer>> entry : dropIndexByArray.entrySet()) {
            ArrayNode arrayNode = (ArrayNode) prunedNode.get(entry.getKey());
            if (arrayNode == null) {
                continue;
            }
            List<Integer> descendingIndexList = new ArrayList<Integer>(entry.getValue());
            Collections.reverse(descendingIndexList);
            for (Integer index : descendingIndexList) {
                if (index.intValue() < arrayNode.size()) {
                    arrayNode.remove(index.intValue());
                }
            }
        }
        return prunedNode;
    }

    /** 只汇总关键字与路径，绝不包含违规值。 */
    private String summarize(Set<ValidationMessage> violationSet) {
        Set<String> keywordSet = new LinkedHashSet<String>();
        Set<String> pathSet = new LinkedHashSet<String>();
        for (ValidationMessage violation : violationSet) {
            keywordSet.add(violation.getType());
            if (pathSet.size() < 12) {
                pathSet.add(violation.getPath());
            }
        }
        return "关键字=" + keywordSet + "，路径=" + pathSet;
    }

    private HealthReportException fail() {
        return new HealthReportException(FailCode.SERVER_ERROR, 500);
    }
}
