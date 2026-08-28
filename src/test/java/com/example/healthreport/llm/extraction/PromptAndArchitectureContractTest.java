package com.example.healthreport.llm.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.infra.ExtractionProperties;
import com.example.healthreport.infra.DishTagModelClient;
import com.example.healthreport.infra.OpenAiCompatibleExtractionModelClient;
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
		.compile("promptVersion\\s*=\\s*([a-z_]+-[0-9]+\\.[0-9]+\\.[0-9]+)");

	private static final String[] HTTP_LOGGER_NAME_ARRAY = { "org.springframework.web.client", "org.apache.http",
			"org.apache.http.wire", "org.apache.http.headers", "org.eclipse.jetty", "jdk.internal.httpclient" };

	@Test
	void promptConstantsHeadersHistoryAndDigestsShouldStayAligned() throws Exception {
		assertThat(PromptVersions.EXTRACTION).isEqualTo("extraction-2.4.0");
		assertThat(PromptVersions.DISH_TAG).isEqualTo("dishtag-2.2.2");
		assertPromptVersion("prompt/extraction.md", PromptVersions.EXTRACTION);
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
		assertHistoryEntry("extraction", PromptVersions.EXTRACTION, "prompt/extraction.md", lastVersionMap,
				lastDigestMap);
		assertHistoryEntry("dishtag", PromptVersions.DISH_TAG, "prompt/dish_tag.md", lastVersionMap, lastDigestMap);
	}

	@Test
	void llmAPromptShouldBePackagedAndStartupShouldRejectBlankConnectionFields() throws Exception {
		ClassPathResource promptResource = new ClassPathResource("prompt/extraction.md");
		assertThat(promptResource.exists()).isTrue();
		assertThat(promptResource.contentLength()).isPositive();

		ExtractionProperties blank = new ExtractionProperties();
		ExtractionStartupValidator validator = new ExtractionStartupValidator(blank, new ExtractionPromptProvider());
		assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(new String[0])))
			.isInstanceOf(IllegalStateException.class);

		ExtractionProperties valid = validProperties();
		assertThat(valid.toString()).doesNotContain("test-api-key");
		new ExtractionStartupValidator(valid, new ExtractionPromptProvider())
			.run(new DefaultApplicationArguments(new String[0]));

		ExtractionProperties missingBaseUrl = validProperties();
		missingBaseUrl.setBaseUrl(" ");
		assertStartupFails(missingBaseUrl);
		ExtractionProperties missingModel = validProperties();
		missingModel.setModel(null);
		assertStartupFails(missingModel);
		ExtractionProperties missingApiKey = validProperties();
		missingApiKey.setApiKey("");
		assertStartupFails(missingApiKey);
	}

	@Test
	void directClientMustNotUseCrossChainRetryOrWholeStringRequestPatterns() throws Exception {
		Path sourceRoot = Paths.get("src/main/java/com/example/healthreport");
		String clientSource = read(sourceRoot.resolve("infra/OpenAiCompatibleExtractionModelClient.java"));
		String statusHandlerSource = read(sourceRoot.resolve("infra/StatusOnlyErrorHandler.java"));

		assertThat(clientSource).contains("setBufferRequestBody(true)")
			.contains("setContentLength(bodyBytes.length)")
			.contains("setInterceptors(new ArrayList<ClientHttpRequestInterceptor>(0))")
			.contains("setErrorHandler(new StatusOnlyErrorHandler())")
			.doesNotContain("HttpEntity<String>")
			.doesNotContain("writeValueAsString")
			.doesNotContain("new StringBuilder")
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
		OpenAiCompatibleExtractionModelClient client = new OpenAiCompatibleExtractionModelClient(
				new com.fasterxml.jackson.databind.ObjectMapper(), validProperties());
		Field restTemplateField = OpenAiCompatibleExtractionModelClient.class.getDeclaredField("restTemplate");
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
			.get("src/test/java/com/example/healthreport/infra/OpenAiCompatibleExtractionModelClientTest.java"));
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

	private ExtractionProperties validProperties() {
		ExtractionProperties properties = new ExtractionProperties();
		properties.setBaseUrl("http://127.0.0.1");
		properties.setModel("test-model");
		properties.setApiKey("test-api-key");
		return properties;
	}

	private void assertStartupFails(ExtractionProperties properties) {
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
