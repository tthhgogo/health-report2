package com.example.healthreport.infra;

import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrContentSplitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测 OCR 实际返回哪种格式，以及切块粒度是否可用。
 *
 * <p><b>为什么需要这个探针。</b> 2026-09-02 实测同一张图三次调用返回三种格式：
 * 纯文本流、{@code <fcel>/<nl>} 表格标记、Markdown 表格。格式本身不影响正确性，
 * 但它决定 {@code OcrContentSplitter} 切出多少块——而 {@code blockRefs} 的全部意义
 * 就是定位到「哪一块」。粒度一垮，证据校验与行归属同时失效。</p>
 *
 * <p><b>用合成图，不用真实报告。</b> 探针只关心格式与块数，不需要真实健康数据；
 * 图由 Java2D 现画，仓库里不留任何测试资产。响应正文一个字都不进日志，只记格式与计数。</p>
 *
 * <pre>
 * export OCR_BASE_URL=... OCR_MODEL=... OCR_API_KEY=...
 * export OCR_PROBE_REPEAT=5          # 可选，默认 5
 * export OCR_PROBE_JSON_SCHEMA=true  # 可选，额外试一次 response_format=json_schema
 * mvn test -Dtest=OcrOutputFormatProbeIT
 * </pre>
 */
@Slf4j(topic = "OCR_OUTPUT_FORMAT_PROBE")
class OcrOutputFormatProbeIT {

    private static final int DEFAULT_REPEAT = 5;

    private static final int CONNECT_TIMEOUT_MILLIS = 30000;

    private static final int READ_TIMEOUT_MILLIS = 180000;

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** logback-test.xml 的 root 是 WARN；本类只输出格式与计数，可以放开到 INFO。 */
    @BeforeEach
    void enableProbeLogs() {
        if (log instanceof ch.qos.logback.classic.Logger) {
            ((ch.qos.logback.classic.Logger) log).setLevel(ch.qos.logback.classic.Level.INFO);
        }
    }

    @Test
    void shouldReportOcrOutputFormatAndBlockGranularity() throws Exception {
        byte[] pngBytes = syntheticTablePng();
        int repeat = intEnv("OCR_PROBE_REPEAT", DEFAULT_REPEAT);
        log.info("probe start: model={} repeat={} imageBytes={}",
                requireEnv("OCR_MODEL"), repeat, pngBytes.length);

        Map<String, Integer> formatCountMap = new LinkedHashMap<String, Integer>();
        Map<Integer, List<String>> missingMarkerMap = new LinkedHashMap<Integer, List<String>>();
        List<Integer> blockCountList = new ArrayList<Integer>();
        for (int round = 0; round < repeat; round++) {
            String content = call(pngBytes, false);
            String format = detectFormat(content);
            List<OcrBlock> blockList = OcrContentSplitter.split(content);
            blockCountList.add(Integer.valueOf(blockList.size()));
            List<String> missingList = missingMarkers(blockList);
            if (!missingList.isEmpty()) {
                missingMarkerMap.put(Integer.valueOf(round + 1), missingList);
                log.warn("  #{} 漏识别标记={}", round + 1, missingList);
            }
            Integer previous = formatCountMap.get(format);
            formatCountMap.put(format, previous == null ? 1 : previous.intValue() + 1);
            // 只记格式、字符数与块数；识别正文一个字都不记。
            log.info("  #{} format={} contentChars={} blocks={}",
                    round + 1, format, content.length(), blockList.size());
        }

        log.info("format distribution: {}", formatCountMap);
        log.info("block counts: {}", blockCountList);
        if (formatCountMap.size() > 1) {
            log.warn("同一张图返回了 {} 种格式——切块必须靠 OcrContentSplitter 归一化，"
                    + "不能指望提示词把格式固定住", formatCountMap.size());
        }

        if (Boolean.parseBoolean(System.getenv().getOrDefault("OCR_PROBE_JSON_SCHEMA", "false"))) {
            probeJsonSchema(pngBytes);
        }

        assertThat(blockCountList).as("每次都必须切出块；恒为 0 说明归一化或识别有问题")
                .allMatch(count -> count.intValue() > 0);
        assertThat(missingMarkerMap)
                .as("识别结果漏了页眉/患者信息/日期/章节标题/表格/页尾里的标记——"
                        + "OCR 的召回率是整条链路的上限，LLM-A 补不回它没识别出来的东西")
                .isEmpty();
    }

    /**
     * 额外试一次约束解码。
     * <p>OCR 是逐字转录任务，强行套 JSON 结构可能损伤识别质量或触发循环——
     * 所以这一项默认关闭，结果只报告不断言。</p>
     */
    private void probeJsonSchema(byte[] pngBytes) {
        try {
            String content = call(pngBytes, true);
            JsonNode node = objectMapper.reader().readTree(content);
            int lineCount = node.path("lines").size();
            log.info("json_schema probe: 接受，lines={} contentChars={}", lineCount, content.length());
        } catch (Exception exception) {
            log.warn("json_schema probe: 不可用，异常类型={}", exception.getClass().getName());
        }
    }

    /** 在切出的块里找唯一标记；比对去掉空白后的拼接文本，容忍分块位置差异。 */
    private List<String> missingMarkers(List<OcrBlock> blockList) {
        StringBuilder builder = new StringBuilder();
        for (OcrBlock block : blockList) {
            builder.append(block.getRawText());
        }
        String flattened = builder.toString().replaceAll("\\s", "");
        List<String> missingList = new ArrayList<String>();
        for (String marker : REQUIRED_MARKER_ARRAY) {
            if (!flattened.contains(marker.replaceAll("\\s", ""))) {
                missingList.add(marker);
            }
        }
        return missingList;
    }

    /** 判定格式；顺序有意——表格标记优先于 Markdown，两者都优先于纯文本。 */
    private String detectFormat(String content) {
        if (content.contains("<nl>") || content.contains("<fcel>") || content.contains("<lcel>")) {
            return "表格标记";
        }
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 2 && trimmed.charAt(0) == '|'
                    && trimmed.charAt(trimmed.length() - 1) == '|') {
                return "Markdown表格";
            }
        }
        return "纯文本";
    }

    /**
     * 合成图必须覆盖的唯一标记：<b>页眉、患者信息、日期、章节标题、表格、页尾各一个</b>。
     *
     * <p>只断言「有输出」是没用的——模型识别出一个词、漏掉姓名栏、日期、章节标题和页尾，
     * 那种测试照样通过。而 IMG_0442 那次实测<b>就是页头信息整段没了</b>。</p>
     */
    private static final String[] REQUIRED_MARKER_ARRAY = {
            "样本医院体检中心", "样本患者甲", "2026-09-02", "生化检查", "样本项乙", "样本页尾标记"};

    /** 现画一张完整页面的小图；全部是合成字样，不含任何真实健康数据。 */
    private byte[] syntheticTablePng() throws IOException {
        BufferedImage image = new BufferedImage(760, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 760, 400);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            graphics.drawString("样本医院体检中心", 30, 40);
            graphics.drawString("姓名 样本患者甲    性别 男    体检日期 2026-09-02", 30, 80);
            graphics.drawString("生化检查", 30, 125);
            String[][] rowArray = {
                    {"项目名称", "检查结果", "单位", "参考值"},
                    {"样本项甲", "1.23", "mmol/L", "0.5~2.0"},
                    {"样本项乙", "4.56", "g/L", "3.0~5.0"}};
            for (int row = 0; row < rowArray.length; row++) {
                for (int column = 0; column < rowArray[row].length; column++) {
                    graphics.drawString(rowArray[row][column], 30 + column * 175, 175 + row * 55);
                }
                graphics.drawLine(20, 190 + row * 55, 740, 190 + row * 55);
            }
            graphics.drawString("样本页尾标记 第 1 页 共 1 页", 30, 370);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    /** 请求体与生产 {@code PaddleOcrVlClient.buildRequestBody} 同形，指令也用生产那一条。 */
    private String call(byte[] pngBytes, boolean withJsonSchema) throws IOException {
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", requireEnv("OCR_MODEL"));
        requestNode.put("temperature", 0);
        ArrayNode contentArray = requestNode.putArray("messages").addObject()
                .put("role", "user").putArray("content");
        contentArray.addObject().put("type", "image_url").putObject("image_url")
                .put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes));
        contentArray.addObject().put("type", "text")
                .put("text", PaddleOcrVlClient.TRANSCRIBE_INSTRUCTION);
        if (withJsonSchema) {
            ObjectNode schemaNode = objectMapper.createObjectNode();
            schemaNode.put("type", "object").put("additionalProperties", false);
            schemaNode.putArray("required").add("lines");
            schemaNode.putObject("properties").putObject("lines")
                    .put("type", "array").putObject("items").put("type", "string");
            ObjectNode jsonSchemaNode = objectMapper.createObjectNode();
            jsonSchemaNode.put("name", "ocr_lines").put("strict", true);
            jsonSchemaNode.set("schema", schemaNode);
            requestNode.putObject("response_format").put("type", "json_schema")
                    .set("json_schema", jsonSchemaNode);
        }

        String url = ocrChatCompletionsUrl();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + requireEnv("OCR_API_KEY"));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(objectMapper.writeValueAsBytes(requestNode));
            }
            int statusCode = connection.getResponseCode();
            byte[] bodyBytes = readBounded(statusCode >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            if (statusCode < 200 || statusCode > 299) {
                throw new AssertionError("OCR 网关返回 " + statusCode
                        + "，响应字节数=" + bodyBytes.length);
            }
            JsonNode contentNode = objectMapper.readTree(bodyBytes)
                    .path("choices").path(0).path("message").path("content");
            if (!contentNode.isTextual()) {
                throw new AssertionError("OCR 响应缺少 message.content");
            }
            return contentNode.asText();
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
                throw new IOException("OCR 响应体超过测试读取上限");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 端点必须显式配置，非本机地址只接受 HTTPS。
     * <p>请求头里带着 Bearer token，明文 HTTP 会让凭证裸奔——与
     * {@code JsonSchemaGatewayVerificationIT} 同一条规则。</p>
     */
    private String ocrChatCompletionsUrl() {
        String baseUrl = requireEnv("OCR_BASE_URL").replaceAll("/+$", "");
        if (baseUrl.startsWith("http://") && !isLoopback(baseUrl)) {
            throw new AssertionError("OCR_BASE_URL 是非本机的明文 HTTP，而请求会携带 Bearer token；"
                    + "请改用 HTTPS，或确认网关只在本机可达");
        }
        return baseUrl + "/v1/chat/completions";
    }

    /** 本机地址允许明文，便于对着本地 mock 调试。 */
    private boolean isLoopback(String url) {
        String host = url.substring("http://".length());
        int end = host.indexOf('/');
        if (end >= 0) {
            host = host.substring(0, end);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    private int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new AssertionError("缺少环境变量 " + name + "；本探针不使用任何默认值");
        }
        return value.trim();
    }
}
