package com.example.healthreport.llm.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.infra.HealthReportAnalysisModelProperties;
import com.example.healthreport.infra.DishTagModelClient;
import com.example.healthreport.infra.OpenAiCompatibleHealthReportAnalysisModelClient;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.env.PropertySource;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** R55a/R55b/R57/R64/R65：提示词版本、打包、启动自检与静态安全边界。 */
class PromptAndArchitectureContractTest {

	private static final Pattern VERSION_PATTERN = Pattern
		.compile("promptVersion\\s*=\\s*([a-z_-]+-[0-9]+\\.[0-9]+\\.[0-9]+)");

	private static final String[] HTTP_LOGGER_NAME_ARRAY = { "org.springframework.web.client", "org.apache.http",
			"org.apache.http.wire", "org.apache.http.headers", "org.eclipse.jetty", "jdk.internal.httpclient" };

	/**
	 * 提示词「输出骨架」里的那份 JSON 必须自己就能通过正式 Schema。
	 *
	 * <p>它是模型唯一会照抄的整体结构。骨架里塞 {@code ""} 或空 {@code blockRefs} 当占位符，
	 * 模型照抄就会被 Schema 拒——而 {@code sections} 不在可剔除白名单里，那是整批失败。
	 * 2026-09-02 曾出现 23 处非法占位值，本断言防它复发。</p>
	 */
	@Test
	void promptOutputSkeletonsMustEachPassTheirProductionSchema() throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		ModelOutputSchemaRegistry registry = new ModelOutputSchemaRegistry(objectMapper);
		for (ExtractionCall call : ExtractionCall.values()) {
			String prompt = read(Paths.get(call.getPromptResource()));
			int sectionStart = prompt.indexOf("## 输出骨架");
			assertThat(sectionStart).as(call + " 提示词缺少「输出骨架」一节").isGreaterThan(0);
			int fenceStart = prompt.indexOf("```json", sectionStart);
			int bodyStart = prompt.indexOf('\n', fenceStart) + 1;
			int bodyEnd = prompt.indexOf("```", bodyStart);
			JsonNode skeletonNode = objectMapper.readTree(prompt.substring(bodyStart, bodyEnd).trim());
			Set<ValidationMessage> violationSet = registry.extraction(call).validate(skeletonNode);
			assertThat(violationSet).as(call + " 输出骨架自身不合 Schema，模型照抄即失败").isEmpty();
		}
	}

	@Test
	void promptConstantsHeadersHistoryAndDigestsShouldStayAligned() throws Exception {
		assertThat(PromptVersions.INDICATORS).isEqualTo("indicators-1.1.0");
		assertThat(PromptVersions.PROBLEMS).isEqualTo("problems-1.0.0");
		assertThat(PromptVersions.DIET_TAGS).isEqualTo("diet-tags-1.1.0");
		assertThat(PromptVersions.DISH_TAG).isEqualTo("dishtag-2.2.3");
		assertPromptVersion("prompt/indicators.md", PromptVersions.INDICATORS);
		assertPromptVersion("prompt/health-problems.md", PromptVersions.PROBLEMS);
		assertPromptVersion("prompt/diet-tags.md", PromptVersions.DIET_TAGS);
		assertPromptVersion("prompt/dish_tag.md", PromptVersions.DISH_TAG);
		assertThat(read(Paths.get("src/main/resources/application.properties")))
			.contains("llm.model-version-extraction=${EXTRACTION_MODEL:}")
			.contains("llm.extraction.model=${llm.model-version-extraction}");

		List<String> historyLineList = Files.readAllLines(Paths.get("prompt/versions.tsv"), StandardCharsets.UTF_8);
		Map<String, String> digestByVersionMap = new HashMap<String, String>();
		Map<String, String> versionByDigestMap = new HashMap<String, String>();
		Map<String, String> lastVersionMap = new HashMap<String, String>();
		Map<String, String> lastDigestMap = new HashMap<String, String>();
		for (String line : historyLineList) {
			String[] fields = line.split("\\t", -1);
			assertThat(fields).hasSize(2);
			String oldDigest = digestByVersionMap.put(fields[0], fields[1]);
			String oldVersion = versionByDigestMap.put(fields[1], fields[0]);
			assertThat(oldDigest == null || oldDigest.equals(fields[1])).isTrue();
			assertThat(oldVersion == null || oldVersion.equals(fields[0])).isTrue();
			lastVersionMap.put(familyOf(fields[0]), fields[0]);
			lastDigestMap.put(familyOf(fields[0]), fields[1]);
		}
		assertHistoryEntry("indicators", PromptVersions.INDICATORS, "prompt/indicators.md", lastVersionMap,
				lastDigestMap);
		assertHistoryEntry("problems", PromptVersions.PROBLEMS, "prompt/health-problems.md", lastVersionMap,
				lastDigestMap);
		assertHistoryEntry("diet-tags", PromptVersions.DIET_TAGS, "prompt/diet-tags.md", lastVersionMap,
				lastDigestMap);
		assertHistoryEntry("dishtag", PromptVersions.DISH_TAG, "prompt/dish_tag.md", lastVersionMap, lastDigestMap);
	}

	@Test
	void dishTagPromptShouldRequireMatchedIngredientNameStrings() throws Exception {
		String dishTagPrompt = read(Paths.get("prompt/dish_tag.md"));

		assertThat(dishTagPrompt)
			.contains("`matchedIngredients` 的元素类型固定为字符串，只填写食材名称")
			.contains("正确：`[\"虾仁\", \"虾皮\"]`")
			.contains("错误：`[{\"name\":\"虾仁\",\"weightG\":10}]`")
			.contains("禁止把输入里的 `{name, weightG}` 食材对象复制到输出")
			.contains("响应正文只能是一个原始 JSON 对象")
			.contains("不得输出 Markdown 代码围栏")
			.contains("不得在 JSON 前后添加说明文字");
	}

	@Test
	void llmAPromptShouldBePackagedAndStartupShouldRejectBlankConnectionFields() throws Exception {
		ClassPathResource promptResource = new ClassPathResource("prompt/indicators.md");
		assertThat(promptResource.exists()).isTrue();
		assertThat(promptResource.contentLength()).isPositive();

		HealthReportAnalysisModelProperties blank = new HealthReportAnalysisModelProperties();
		ExtractionStartupValidator validator = new ExtractionStartupValidator(blank, new ExtractionPromptProvider());
		assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
			.isInstanceOf(IllegalStateException.class);

		HealthReportAnalysisModelProperties valid = validProperties();
		assertThat(valid.toString()).doesNotContain("test-api-key");
		new ExtractionStartupValidator(valid, new ExtractionPromptProvider())
			.run(new DefaultApplicationArguments(new String[0]));

		HealthReportAnalysisModelProperties missingBaseUrl = validProperties();
		missingBaseUrl.setBaseUrl(" ");
		assertStartupFails(missingBaseUrl);
		HealthReportAnalysisModelProperties missingModel = validProperties();
		missingModel.setModel(null);
		assertStartupFails(missingModel);
		HealthReportAnalysisModelProperties missingApiKey = validProperties();
		missingApiKey.setApiKey("");
		assertStartupFails(missingApiKey);
	}

	@Test
	void directClientMustNotUseCrossChainRetryOrWholeStringRequestPatterns() throws Exception {
		Path sourceRoot = Paths.get("src/main/java/com/example/healthreport");
		String clientSource = read(sourceRoot.resolve("infra/OpenAiCompatibleHealthReportAnalysisModelClient.java"));
		String statusHandlerSource = read(sourceRoot.resolve("infra/StatusOnlyErrorHandler.java"));

		assertThat(clientSource).contains("setBufferRequestBody(true)")
			.contains("setContentLength(bodyBytes.length)")
			.doesNotContain("Interceptor")
			.contains("setErrorHandler(new StatusOnlyErrorHandler())")
			.doesNotContain("HttpEntity<String>")
			.doesNotContain("writeValueAsString")
			.doesNotContain("DishTagModelClient")
			.doesNotContain("RestClientResponseException")
			.doesNotContain("RetryTemplate");
		assertThat(clientSource.indexOf("long estimatedBytes = estimateBodyBytes(input)"))
			.isLessThan(clientSource.indexOf("Base64.getEncoder().encodeToString"));
		assertThat(statusHandlerSource).doesNotContain("response.getBody(");

		Set<String> llmASourceSet = new HashSet<String>();
		Files.walk(sourceRoot.resolve("llm/extraction"))
			.filter(Files::isRegularFile)
			.forEach(path -> llmASourceSet.add(readUnchecked(path)));
		// Dify 已随 LLM-B 改直连一并删除；现在要拦的是两条直连链路互相串味。
		assertThat(llmASourceSet).allSatisfy(source -> assertThat(source).doesNotContain("DishTagModelClient"));

		ArchRule directOnlyRule = noClasses().that()
			.resideInAPackage("..llm.extraction..")
			.should()
			.dependOnClassesThat()
			.areAssignableTo(DishTagModelClient.class);
		directOnlyRule.check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.example.healthreport.llm.extraction"));
	}

	@Test
	void llmAHttpClientMustHaveNoInterceptorsOrUnsafeWireLoggingConfiguration() throws Exception {
		OpenAiCompatibleHealthReportAnalysisModelClient client = new OpenAiCompatibleHealthReportAnalysisModelClient(
				new com.fasterxml.jackson.databind.ObjectMapper(), validProperties());
		Field restTemplateField = OpenAiCompatibleHealthReportAnalysisModelClient.class.getDeclaredField("restTemplate");
		restTemplateField.setAccessible(true);
		RestTemplate restTemplate = (RestTemplate) restTemplateField.get(client);

		assertThat(restTemplate.getInterceptors()).isEmpty();
		List<PropertySource<?>> propertySourceList = new PropertiesPropertySourceLoader().load("application",
				new FileSystemResource("src/main/resources/application.properties"));
		assertLoggingLevel(propertySourceList, "org.springframework.web.client", "INFO");
		assertLoggingLevel(propertySourceList, "org.apache.http", "INFO");
		assertLoggingLevel(propertySourceList, "org.apache.http.wire", "OFF");
		assertLoggingLevel(propertySourceList, "org.apache.http.headers", "OFF");
		assertLoggingLevel(propertySourceList, "org.eclipse.jetty", "INFO");
		assertLoggingLevel(propertySourceList, "jdk.internal.httpclient", "INFO");

		LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (String loggerName : HTTP_LOGGER_NAME_ARRAY) {
			Logger httpLogger = loggerContext.getLogger(loggerName);
			assertThat(httpLogger.getEffectiveLevel().isGreaterOrEqual(Level.INFO))
				.as("HTTP 日志器 %s 的生效级别不得为 DEBUG/TRACE", loggerName)
				.isTrue();
		}
	}

	@Test
	void wireMockRedLineTestsMustNeverBeConditionallySkipped() throws Exception {
		String wireMockTestSource = read(Paths
			.get("src/test/java/com/example/healthreport/infra/OpenAiCompatibleHealthReportAnalysisModelClientTest.java"));
		assertThat(wireMockTestSource).doesNotContain("Assumptions")
			.doesNotContain("assumeTrue")
			.doesNotContain("@Disabled");
	}

	private void assertPromptVersion(String path, String expectedVersion) throws Exception {
		String prompt = read(Paths.get(path));
		Matcher matcher = VERSION_PATTERN.matcher(prompt);
		assertThat(matcher.find()).isTrue();
		assertThat(matcher.group(1)).isEqualTo(expectedVersion);
	}

	private void assertLoggingLevel(List<PropertySource<?>> propertySourceList, String loggerName,
			String expectedLevel) {
		Object configuredLevel = null;
		for (PropertySource<?> propertySource : propertySourceList) {
			Object candidate = propertySource.getProperty("logging.level." + loggerName);
			if (candidate != null) {
				configuredLevel = candidate;
				break;
			}
		}
		assertThat(configuredLevel).as("生产配置必须显式限制 HTTP 日志器 %s", loggerName).isEqualTo(expectedLevel);
	}

	private HealthReportAnalysisModelProperties validProperties() {
		HealthReportAnalysisModelProperties properties = new HealthReportAnalysisModelProperties();
		properties.setBaseUrl("http://127.0.0.1");
		properties.setModel("test-model");
		properties.setApiKey("test-api-key");
		return properties;
	}

	private void assertStartupFails(HealthReportAnalysisModelProperties properties) {
		assertThatThrownBy(() -> new ExtractionStartupValidator(properties, new ExtractionPromptProvider())
			.run(new DefaultApplicationArguments(new String[0]))).isInstanceOf(IllegalStateException.class);
	}

	private void assertHistoryEntry(String family, String expectedVersion, String promptPath,
			Map<String, String> lastVersionMap, Map<String, String> lastDigestMap) throws Exception {
		assertThat(lastVersionMap.get(family)).isEqualTo(expectedVersion);
		assertThat(lastDigestMap.get(family)).isEqualTo(sha256(Paths.get(promptPath)));
	}

	/** 版本标识形如 extraction-2.3.1，最后一个连字符之前是提示词族名。 */
	private String familyOf(String version) {
		return version.substring(0, version.lastIndexOf('-'));
	}

	private String sha256(Path path) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
		StringBuilder hex = new StringBuilder(digest.length * 2);
		for (byte value : digest) {
			hex.append(String.format("%02x", value & 0xff));
		}
		return hex.toString();
	}

	private String read(Path path) throws Exception {
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}

	private String readUnchecked(Path path) {
		try {
			return read(path);
		}
		catch (Exception exception) {
			throw new IllegalStateException("测试源码读取失败", exception);
		}
	}

}
