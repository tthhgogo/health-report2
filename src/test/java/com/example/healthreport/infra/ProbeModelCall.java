package com.example.healthreport.infra;

import com.example.healthreport.render.CompressedPageImage;
import com.example.healthreport.render.ExtractionImageCompressor;
import com.example.healthreport.render.pdf.PdfPageRenderer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * 探针共用的模型调用管道：PDF 逐页渲染 → 压缩 JPEG → Base64 → 直连网关 → 原始响应落盘。
 *
 * <p><b>只服务于探针，不属于生产链路。</b> 抽出来是因为「纯图像抽取」与「指标召回率」
 * 两个探针的这一段完全相同，各写一份必然分叉——一边改了超时或落盘规则，
 * 另一边悄悄留在旧行为上，而两边的结论还会被放在一起比较。</p>
 *
 * <p>渲染沿用生产组件 {@link PdfPageRenderer} 与 {@link ExtractionImageCompressor}，
 * 探针与生产链路的差异必须只有「输入形态」这一个变量。</p>
 */
final class ProbeModelCall {

    /** 一次调用的结果：HTTP 状态码与（流式时拼回的）标准信封。 */
    static final class Result {
        final int httpStatus;
        final String envelope;
        final long elapsedMillis;

        Result(int httpStatus, String envelope, long elapsedMillis) {
            this.httpStatus = httpStatus;
            this.envelope = envelope;
            this.elapsedMillis = elapsedMillis;
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Path outputDir;

    ProbeModelCall(String baseUrl, String model, String apiKey, Path outputDir) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.outputDir = outputDir;
    }

    // ---------- PDF → JPEG ----------

    /**
     * 渲染 {@code [pageFrom, pageFrom+maxPages)} 这一段页面，逐页压缩后立即释放源位图
     * ——300dpi 整份缓存会直接吃掉堆。
     *
     * <p><b>为什么要有页区间。</b> 整份一次发过去撞网关时长上限时，客户端唯一能做的
     * 就是缩小单次规模；只有「取前 N 页」的话，第 N+1 页往后永远测不到。</p>
     */
    static List<byte[]> renderPdfPages(Path pdf, int pageFrom, int maxPages) throws IOException {
        PdfPageRenderer renderer = new PdfPageRenderer();
        ExtractionImageCompressor compressor = new ExtractionImageCompressor();
        List<byte[]> pageImageList = new ArrayList<byte[]>();
        PDDocument document = PDDocument.load(pdf.toFile());
        try {
            int totalPages = document.getNumberOfPages();
            if (pageFrom > totalPages) {
                throw new IllegalArgumentException(
                        "probe.pageFrom=" + pageFrom + " exceeds the page count " + totalPages);
            }
            int firstIndex = Math.max(0, pageFrom - 1);
            int lastExclusive = Math.min(totalPages, firstIndex + maxPages);
            System.out.println("[probe] page window " + (firstIndex + 1) + ".." + lastExclusive
                    + " of " + totalPages
                    + (firstIndex > 0 || lastExclusive < totalPages
                            ? "  (partial: findings cover this window only)" : ""));
            for (int pageIndex = firstIndex; pageIndex < lastExclusive; pageIndex++) {
                BufferedImage rendered = renderer.render(document, pageIndex);
                try {
                    CompressedPageImage compressed = compressor.compressForExtraction(rendered);
                    pageImageList.add(compressed.getJpegBytes());
                } finally {
                    rendered.flush();
                }
            }
        } finally {
            document.close();
        }
        return pageImageList;
    }

    /**
     * 只取 {@code ## System} 之后的正文。
     *
     * <p>提示词开头那段是给人看的说明，常含生产版才有的字段名，
     * 原样发出去会诱导模型输出本版不存在的字段。</p>
     */
    static String loadPromptBody(Path promptPath) throws IOException {
        String text = new String(Files.readAllBytes(promptPath), StandardCharsets.UTF_8);
        int index = text.indexOf("\n## System");
        return index < 0 ? text : text.substring(index + 1);
    }

    // ---------- 调用 ----------

    /** 组包、发送、落盘原始响应；返回状态码与信封，断言交给调用方。 */
    Result call(String systemPrompt, List<byte[]> pageImageList, String userText, int maxTokens)
            throws IOException {
        boolean streaming = booleanSetting("probe.stream");
        byte[] body = buildRequestBody(systemPrompt, pageImageList, userText, maxTokens, streaming);
        System.out.println(String.format(
                "[probe] pages=%d images=%d KB prompt=%d chars body=%.1f MB",
                pageImageList.size(), totalBytes(pageImageList) / 1024,
                systemPrompt.length(), body.length / 1024D / 1024D));
        System.out.println("[probe] transport=" + (streaming ? "SSE streaming" : "non-streaming")
                + " enable_thinking=" + setting("probe.thinking", "PROBE_THINKING", "false"));

        Files.createDirectories(outputDir);
        long startMillis = System.currentTimeMillis();
        int httpStatus;
        String envelope;
        if (streaming) {
            Result streamed = postStreaming(body, startMillis);
            httpStatus = streamed.httpStatus;
            envelope = streamed.envelope;
        } else {
            ResponseEntity<String> response = post(body);
            httpStatus = response.getStatusCodeValue();
            envelope = response.getBody() == null ? "" : response.getBody();
        }
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        // 【先落盘，再断言】截断、双通道冲突、网关报错都发生在调用方的断言里，
        // 而那几种情况恰恰最需要看原始返回。放到断言之后写，等于每次失败都把证据一起丢了。
        Path rawPath = outputDir.resolve("probe-raw-response.json");
        Files.write(rawPath, envelope.getBytes(StandardCharsets.UTF_8));
        System.out.println("[probe] raw response written to " + rawPath.toAbsolutePath());
        printEnvelopeStatistics(envelope, elapsedMillis);
        return new Result(httpStatus, envelope, elapsedMillis);
    }

    private byte[] buildRequestBody(String systemPrompt, List<byte[]> pageImageList,
                                    String userText, int maxTokens, boolean streaming)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JsonGenerator generator = objectMapper.getFactory().createGenerator(output);
        try {
            generator.writeStartObject();
            generator.writeStringField("model", model);
            generator.writeNumberField("temperature", 0);
            generator.writeBooleanField("stream", streaming);
            writeThinkingSwitch(generator);
            if (streaming && booleanSetting("probe.streamUsage")) {
                // 不少网关不认这个字段，默认关闭；只有确认支持时才开，用来在流末尾拿 usage。
                generator.writeObjectFieldStart("stream_options");
                generator.writeBooleanField("include_usage", true);
                generator.writeEndObject();
            }
            if (maxTokens > 0) {
                generator.writeNumberField("max_tokens", maxTokens);
            }
            generator.writeArrayFieldStart("messages");
            generator.writeStartObject();
            generator.writeStringField("role", "system");
            generator.writeStringField("content", systemPrompt);
            generator.writeEndObject();
            generator.writeStartObject();
            generator.writeStringField("role", "user");
            generator.writeArrayFieldStart("content");
            generator.writeStartObject();
            generator.writeStringField("type", "text");
            generator.writeStringField("text", userText);
            generator.writeEndObject();
            for (byte[] jpegBytes : pageImageList) {
                generator.writeStartObject();
                generator.writeStringField("type", "image_url");
                generator.writeObjectFieldStart("image_url");
                // mime 必须与实际字节一致；这里始终是 ExtractionImageCompressor 产出的 JPEG。
                generator.writeStringField("url", "data:image/jpeg;base64,"
                        + Base64.getEncoder().encodeToString(jpegBytes));
                generator.writeEndObject();
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
            generator.writeEndArray();
            generator.writeObjectFieldStart("response_format");
            generator.writeStringField("type", "json_object");
            generator.writeEndObject();
            generator.writeEndObject();
            generator.flush();
        } finally {
            generator.close();
        }
        return output.toByteArray();
    }

    /**
     * 关掉模型自身的思考段（Qwen3 等模板走 {@code chat_template_kwargs}）。
     *
     * <p><b>为什么默认关。</b> 思考模式会在给出 JSON 之前先生成一大段推理，输出 token 常翻几倍
     * ——而这条路线的瓶颈恰恰是 decode 时长（设计方案 §4.1.5：约 106 token/s），
     * 再叠一段思考很容易撞上网关的时长上限。探针要测的是能不能把数据抽全，不是思考得好不好。</p>
     *
     * <p>三态：{@code false}（默认）、{@code true}、{@code default}（整个字段不发）。
     * 留出第三态是因为<b>不认识这个字段的网关会直接 400</b>。</p>
     */
    private void writeThinkingSwitch(JsonGenerator generator) throws IOException {
        String thinking = setting("probe.thinking", "PROBE_THINKING", "false");
        if ("default".equalsIgnoreCase(thinking) || "omit".equalsIgnoreCase(thinking)) {
            return;
        }
        generator.writeObjectFieldStart("chat_template_kwargs");
        generator.writeBooleanField("enable_thinking", Boolean.parseBoolean(thinking));
        generator.writeEndObject();
    }

    private ResponseEntity<String> post(byte[] body) {
        RestTemplate restTemplate = new RestTemplate(requestFactory());
        restTemplate.setErrorHandler(nonThrowingErrorHandler());
        HttpHeaders headers = jsonHeaders(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(baseUrl + "/v1/chat/completions", HttpMethod.POST,
                new HttpEntity<byte[]>(body, headers), String.class);
    }

    /**
     * SSE 流式读取；累加完成后<b>拼回一个标准信封</b>，下游解析与非流式完全共用一条路。
     *
     * <p>生产客户端禁用流式是对的（有界读取、避免等长连接关闭）。探针需要它是因为另一个问题：
     * 非流式请求在生成完之前连接上一个字节都不动，按空闲超时掐连接的网关会直接回 504。</p>
     */
    private Result postStreaming(final byte[] body, final long startMillis) throws IOException {
        RestTemplate restTemplate = new RestTemplate(requestFactory());
        restTemplate.setErrorHandler(nonThrowingErrorHandler());
        final HttpHeaders headers = jsonHeaders(MediaType.TEXT_EVENT_STREAM);
        final StringBuilder rawStream = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final String[] finishReason = new String[] {""};
        final int[] httpStatus = new int[] {0};
        final ObjectNode usage = objectMapper.createObjectNode();
        Path streamPath = outputDir.resolve("probe-raw-stream.txt");
        try {
            restTemplate.execute(baseUrl + "/v1/chat/completions", HttpMethod.POST,
                    new RequestCallback() {
                        @Override
                        public void doWithRequest(ClientHttpRequest request) throws IOException {
                            request.getHeaders().putAll(headers);
                            request.getHeaders().setContentLength(body.length);
                            request.getBody().write(body);
                        }
                    },
                    new ResponseExtractor<Void>() {
                        @Override
                        public Void extractData(ClientHttpResponse response) throws IOException {
                            httpStatus[0] = response.getRawStatusCode();
                            readStream(response, rawStream, content, reasoning, finishReason,
                                    usage, httpStatus[0], startMillis);
                            return null;
                        }
                    });
        } finally {
            // 【无论中途怎么断都要留证】读超时发生在流中间时，已经收到的那部分是唯一线索。
            Files.write(streamPath, rawStream.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("[probe] raw SSE written to " + streamPath.toAbsolutePath()
                    + " (" + rawStream.length() + " chars received, content=" + content.length()
                    + ", reasoning=" + reasoning.length() + ")");
        }
        if (httpStatus[0] != 200) {
            return new Result(httpStatus[0], rawStream.toString(), 0L);
        }
        return new Result(200, rebuildEnvelope(content.toString(), reasoning.toString(),
                finishReason[0], usage), 0L);
    }

    private void readStream(ClientHttpResponse response, StringBuilder rawStream,
                            StringBuilder content, StringBuilder reasoning,
                            String[] finishReason, ObjectNode usage, int status,
                            long startMillis) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
        String line;
        int printedLength = 0;
        boolean firstChunkSeen = false;
        while ((line = reader.readLine()) != null) {
            rawStream.append(line).append('\n');
            if (status != 200 || !line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            if (!firstChunkSeen) {
                firstChunkSeen = true;
                // 【首块延迟是关键分岔】几十张图的 prefill 很重，首 token 迟迟不来时，
                // 按空闲超时掐连接的网关会在一个字都没吐出来时就断流——那种情况减页有用；
                // 首块很快、生成到一半才断的是总时长上限，减页才是唯一解。
                System.out.println("[probe] first chunk after "
                        + (System.currentTimeMillis() - startMillis) / 1000D + "s");
            }
            JsonNode chunk;
            try {
                chunk = objectMapper.readTree(payload);
            } catch (IOException exception) {
                // 单个分块解析不了不该中断整流：原文已进 rawStream，跳过继续读。
                continue;
            }
            if (chunk.path("usage").isObject()) {
                usage.setAll((ObjectNode) chunk.path("usage"));
            }
            JsonNode choice = chunk.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            content.append(delta.path("content").asText(""));
            reasoning.append(delta.path("reasoning_content").asText(""));
            if (choice.path("finish_reason").isTextual()) {
                finishReason[0] = choice.path("finish_reason").asText();
            }
            int total = content.length() + reasoning.length();
            if (total - printedLength >= 4000) {
                printedLength = total;
                System.out.println("[probe] generating... received " + total + " chars");
            }
        }
    }

    private String rebuildEnvelope(String content, String reasoning,
                                   String finishReason, ObjectNode usage) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        ObjectNode message = choice.putObject("message");
        message.put("content", content);
        message.put("reasoning_content", reasoning);
        root.set("usage", usage);
        return objectMapper.writeValueAsString(root);
    }

    private void printEnvelopeStatistics(String envelope, long elapsedMillis) {
        try {
            JsonNode root = objectMapper.readTree(envelope);
            JsonNode usage = root.path("usage");
            JsonNode message = root.path("choices").path(0).path("message");
            System.out.println(String.format(
                    "[probe] elapsed=%.1fs finish_reason=%s prompt_tokens=%s completion_tokens=%s "
                            + "content=%d chars reasoning_content=%d chars",
                    elapsedMillis / 1000D,
                    root.path("choices").path(0).path("finish_reason").asText("(missing)"),
                    usage.path("prompt_tokens").asText("?"),
                    usage.path("completion_tokens").asText("?"),
                    message.path("content").asText("").length(),
                    message.path("reasoning_content").asText("").length()));
        } catch (IOException exception) {
            System.out.println("[probe] response is not JSON; see the raw dump");
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(intSetting("probe.connectTimeoutMillis", 10000));
        // 生产是 180 秒；探针要看的是【能不能输出全】，不能让生产超时把答案截掉。
        factory.setReadTimeout(intSetting("probe.readTimeoutMillis", 900000));
        factory.setBufferRequestBody(true);
        return factory;
    }

    private HttpHeaders jsonHeaders(MediaType accept) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(accept));
        headers.setBearerAuth(apiKey);
        return headers;
    }

    /**
     * 【不用生产的 StatusOnlyErrorHandler】它在 exchange 里就把非 2xx 抛成 HealthReportAnalysisCallException，
     * 响应正文连读都读不到——而网关报错时那段正文（限流原因、模型名写错、上下文超限）
     * 正是唯一有用的信息。探针把它原样落到本地文件，不进日志，与生产的脱敏红线不冲突。
     */
    private ResponseErrorHandler nonThrowingErrorHandler() {
        return new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse response) {
                // 状态码交给调用处断言，这里不抛。
            }
        };
    }

    private long totalBytes(List<byte[]> pageImageList) {
        long total = 0L;
        for (byte[] bytes : pageImageList) {
            total += bytes.length;
        }
        return total;
    }

    // ---------- 配置读取 ----------

    static String setting(String systemProperty, String environmentName, String defaultValue) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environmentName);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    static int intSetting(String systemProperty, int defaultValue) {
        String value = System.getProperty(systemProperty);
        return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
    }

    static boolean booleanSetting(String systemProperty) {
        return Boolean.parseBoolean(System.getProperty(systemProperty, "false"));
    }
}
