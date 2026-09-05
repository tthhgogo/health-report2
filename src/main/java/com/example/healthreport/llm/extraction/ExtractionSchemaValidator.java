package com.example.healthreport.llm.extraction;

import com.example.healthreport.llm.schema.ModelOutputSchema;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三次调用共用的 Schema 校验：可定位到单个条目的违规按条目剔除，其余整阶段作废。
 *
 * <p><b>为什么不一票否决</b>：整批作废模式下一个阶段的通过率是 {@code (1-p)^条目数}，
 * 失败率被条目数指数放大（设计方案 §4.4）。可剔除的只有各阶段的业务条目数组；
 * 顶层字段坏了剔除救不回来，直接失败。</p>
 *
 * <p>同一阶段的剔除共用 20% 修复预算（至少允许 1 条），
 * 超预算即该阶段失败，任务 {@code FAILED / SERVER_ERROR}（开发方案 §6.5）。</p>
 */
@Slf4j
@Component
public class ExtractionSchemaValidator {

    /** 单阶段可剔除条目占比上限；大比例剔除说明整阶段跑偏，响亮失败好过残缺放行。 */
    private static final double MAX_DROP_RATIO = 0.20D;

    /** 无论占比如何都允许剔除的条目数下限，防止小样本一条即超预算。 */
    private static final int MIN_ALLOWED_DROPS = 1;

    /** 各调用允许按条目剔除的数组路径模式：捕获组 1 = 容器指针、组 2 = 条目下标。 */
    private static final Pattern INDICATOR_ITEM_PATTERN =
            Pattern.compile("^\\$\\.(sections\\[\\d+\\]\\.indicators)\\[(\\d+)\\]");
    private static final Pattern PATIENT_ITEM_PATTERN =
            Pattern.compile("^\\$\\.(patients)\\[(\\d+)\\]");
    /** 章节自身的违规（如 page 不满足最小值）：整章剔除，预算按该章条目数计（R20）。 */
    private static final Pattern SECTION_ITEM_PATTERN =
            Pattern.compile("^\\$\\.(sections)\\[(\\d+)\\]");
    private static final Pattern PROBLEM_ITEM_PATTERN =
            Pattern.compile("^\\$\\.(problems)\\[(\\d+)\\]");
    private static final Pattern DIET_TAG_ITEM_PATTERN =
            Pattern.compile("^\\$\\.(recommend|reject)\\[(\\d+)\\]");

    private final ObjectMapper objectMapper;
    private final ModelOutputSchemaRegistry schemaRegistry;

    public ExtractionSchemaValidator(ObjectMapper objectMapper,
                                     ModelOutputSchemaRegistry schemaRegistry) {
        if (objectMapper == null || schemaRegistry == null) {
            throw new IllegalArgumentException("Schema 校验依赖不能为空");
        }
        this.objectMapper = objectMapper;
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * 解析并校验模型响应。
     *
     * @return 已通过对应生产 Schema 的输出与剔除统计
     * @throws HealthReportException 违规无法用条目剔除消除时固定映射 SERVER_ERROR，调用方不得重试
     */
    public SchemaValidationOutcome validate(ExtractionCall call, String rawContent) {
        JsonNode rootNode;
        try {
            // 尾随内容必须拒绝：默认配置会静默丢掉 JSON 之后的解释性文字。
            rootNode = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(rawContent);
        } catch (IOException | RuntimeException exception) {
            log.error("模型输出 Schema 校验失败，call={}，阶段=JSON解析，异常类型={}",
                    call, exception.getClass().getName());
            throw fail();
        }
        if (rootNode == null || !rootNode.isObject()) {
            log.error("模型输出 Schema 校验失败，call={}，阶段=根节点不是对象", call);
            throw fail();
        }

        ModelOutputSchema schema = schemaRegistry.extraction(call);
        Set<ValidationMessage> violationSet = validateQuietly(schema, rootNode, call);
        if (violationSet.isEmpty()) {
            return new SchemaValidationOutcome(rootNode, 0);
        }

        Map<String, Set<Integer>> dropIndexByContainer = planDrops(call, violationSet);
        if (dropIndexByContainer == null) {
            throw fail();
        }
        int droppedCount = 0;
        for (Map.Entry<String, Set<Integer>> entry : dropIndexByContainer.entrySet()) {
            if ("sections".equals(entry.getKey())) {
                // 整章剔除按该章条目数入账，防止「一章 30 条按 1 条计」绕过预算。
                for (Integer sectionIndex : entry.getValue()) {
                    droppedCount += Math.max(1, rootNode.path("sections")
                            .path(sectionIndex.intValue()).path("indicators").size());
                }
            } else {
                droppedCount += entry.getValue().size();
            }
        }
        if (exceedsDropBudget(call, rootNode, droppedCount)) {
            throw fail();
        }
        JsonNode prunedNode = pruneItems(rootNode, dropIndexByContainer, call);

        Set<ValidationMessage> remainingSet = validateQuietly(schema, prunedNode, call);
        if (!remainingSet.isEmpty()) {
            log.error("模型输出 Schema 校验失败，call={}，阶段=剔除后仍不合法，剩余违规数={}",
                    call, remainingSet.size());
            throw fail();
        }
        log.info("模型输出条目剔除完成，call={}，剔除条目数={}", call, droppedCount);
        return new SchemaValidationOutcome(prunedNode, droppedCount);
    }

    /**
     * {@code ValidationMessage} 正文可能带模型输出的值（患者姓名、检验结果），一个字都不记。
     */
    private Set<ValidationMessage> validateQuietly(ModelOutputSchema schema, JsonNode node,
                                                   ExtractionCall call) {
        try {
            return schema.validate(node);
        } catch (RuntimeException exception) {
            log.error("模型输出 Schema 校验失败，call={}，阶段=校验器异常，异常类型={}",
                    call, exception.getClass().getName());
            throw fail();
        }
    }

    /**
     * 把违规归到可剔除条目上；任一违规定位不到即返回 {@code null}（整阶段作废）。
     */
    private Map<String, Set<Integer>> planDrops(ExtractionCall call,
                                                Set<ValidationMessage> violationSet) {
        Map<String, Set<Integer>> resultMap = new LinkedHashMap<String, Set<Integer>>();
        for (ValidationMessage violation : violationSet) {
            Matcher matcher = matchDroppable(call, violation.getPath());
            if (matcher == null) {
                log.error("模型输出 Schema 校验失败，call={}，阶段=违规无法定位到可剔除条目，"
                                + "关键字={}，路径={}，违规总数={}",
                        call, violation.getType(), violation.getPath(), violationSet.size());
                return null;
            }
            // ValidationMessage 正文可能包含患者姓名、指标值或原文，只记录稳定关键字和数组路径。
            log.warn("模型输出 Schema 条目不合格，call={}，路径={}，关键字={}，处理=剔除",
                    call, violation.getPath(), violation.getType());
            String containerPath = matcher.group(1);
            Set<Integer> indexSet = resultMap.get(containerPath);
            if (indexSet == null) {
                indexSet = new TreeSet<Integer>();
                resultMap.put(containerPath, indexSet);
            }
            indexSet.add(Integer.valueOf(matcher.group(2)));
        }
        return resultMap;
    }

    private Matcher matchDroppable(ExtractionCall call, String violationPath) {
        List<Pattern> patternList = new ArrayList<Pattern>(2);
        switch (call) {
            case INDICATORS:
                patternList.add(INDICATOR_ITEM_PATTERN);
                patternList.add(PATIENT_ITEM_PATTERN);
                // 必须排在最后：sections[n].indicators[m] 也能被它匹配，特异度低的兜底。
                patternList.add(SECTION_ITEM_PATTERN);
                break;
            case PROBLEMS:
                patternList.add(PROBLEM_ITEM_PATTERN);
                break;
            case DIET_TAGS:
            default:
                patternList.add(DIET_TAG_ITEM_PATTERN);
                break;
        }
        for (Pattern pattern : patternList) {
            Matcher matcher = pattern.matcher(violationPath);
            if (matcher.find()) {
                return matcher;
            }
        }
        return null;
    }

    /** 分母是该阶段的全部可剔除条目数；条目总数为 0 却有条目违规不可能发生，防御性按失败处理。 */
    private boolean exceedsDropBudget(ExtractionCall call, JsonNode rootNode, int droppedCount) {
        int totalItems = countItems(call, rootNode);
        if (totalItems <= 0) {
            return true;
        }
        int allowed = Math.max(MIN_ALLOWED_DROPS, (int) Math.floor(totalItems * MAX_DROP_RATIO));
        if (droppedCount > allowed) {
            log.error("模型输出条目剔除超预算，call={}，剔除条目数={}，条目总数={}，预算={}",
                    call, droppedCount, totalItems, allowed);
            return true;
        }
        return false;
    }

    private int countItems(ExtractionCall call, JsonNode rootNode) {
        switch (call) {
            case INDICATORS: {
                int count = rootNode.path("patients").size();
                for (JsonNode sectionNode : rootNode.path("sections")) {
                    count += sectionNode.path("indicators").size();
                }
                return count;
            }
            case PROBLEMS:
                return rootNode.path("problems").size();
            case DIET_TAGS:
            default:
                return rootNode.path("recommend").size() + rootNode.path("reject").size();
        }
    }

    /** 按容器路径逐个删除条目；下标从大到小删避免位移。 */
    private JsonNode pruneItems(JsonNode rootNode, Map<String, Set<Integer>> dropIndexByContainer,
                                ExtractionCall call) {
        JsonNode workingNode = rootNode.deepCopy();
        List<Map.Entry<String, Set<Integer>>> orderedEntryList =
                new ArrayList<Map.Entry<String, Set<Integer>>>(dropIndexByContainer.entrySet());
        // 嵌套容器（sections[n].indicators）先处理，顶层 sections 最后处理：
        // 先移整章会让嵌套容器引用的章下标整体位移。
        Collections.sort(orderedEntryList, new Comparator<Map.Entry<String, Set<Integer>>>() {
            @Override
            public int compare(Map.Entry<String, Set<Integer>> left,
                               Map.Entry<String, Set<Integer>> right) {
                boolean leftTopSections = "sections".equals(left.getKey());
                boolean rightTopSections = "sections".equals(right.getKey());
                return Boolean.compare(leftTopSections, rightTopSections);
            }
        });
        for (Map.Entry<String, Set<Integer>> entry : orderedEntryList) {
            JsonNode containerNode = resolveContainer(workingNode, entry.getKey());
            if (!(containerNode instanceof ArrayNode)) {
                log.error("模型输出条目剔除失败，call={}，阶段=容器不是数组，容器={}", call, entry.getKey());
                throw fail();
            }
            ArrayNode arrayNode = (ArrayNode) containerNode;
            List<Integer> descendingList = new ArrayList<Integer>(entry.getValue());
            for (int position = descendingList.size() - 1; position >= 0; position--) {
                int dropIndex = descendingList.get(position).intValue();
                if (dropIndex < 0 || dropIndex >= arrayNode.size()) {
                    log.error("模型输出条目剔除失败，call={}，阶段=下标越界，容器={}", call, entry.getKey());
                    throw fail();
                }
                arrayNode.remove(dropIndex);
            }
        }
        // 剔除后 indicators 变空的章节整个移除：Schema 要求每章节至少一条指标。
        if (call == ExtractionCall.INDICATORS && workingNode.path("sections").isArray()) {
            ArrayNode sectionsNode = (ArrayNode) workingNode.path("sections");
            Iterator<JsonNode> sectionIterator = sectionsNode.iterator();
            while (sectionIterator.hasNext()) {
                if (sectionIterator.next().path("indicators").size() == 0) {
                    sectionIterator.remove();
                }
            }
        }
        return workingNode;
    }

    /** 把 {@code sections[3].indicators} 这类容器路径解析到节点。 */
    private JsonNode resolveContainer(JsonNode rootNode, String containerPath) {
        JsonNode currentNode = rootNode;
        for (String segment : containerPath.split("\\.")) {
            int bracket = segment.indexOf('[');
            if (bracket < 0) {
                currentNode = currentNode.path(segment);
            } else {
                String fieldName = segment.substring(0, bracket);
                int arrayIndex = Integer.parseInt(
                        segment.substring(bracket + 1, segment.length() - 1));
                currentNode = currentNode.path(fieldName).path(arrayIndex);
            }
        }
        return currentNode;
    }

    private HealthReportException fail() {
        return new HealthReportException(FailCode.SERVER_ERROR, 500);
    }
}
