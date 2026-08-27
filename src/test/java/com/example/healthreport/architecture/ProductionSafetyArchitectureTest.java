package com.example.healthreport.architecture;

import com.example.healthreport.infra.DishTagModelClient;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 日志、图片上传和 LLM 客户端边界的生产架构断言。 */
class ProductionSafetyArchitectureTest {

    @Test
    void productionCodeShouldNotUseConsoleOrPrintStackTrace() {
        productionClassesRule(new ArchCondition<JavaClass>("不直接写控制台或调用 printStackTrace") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    String ownerName = call.getTarget().getOwner().getFullName();
                    String methodName = call.getTarget().getName();
                    boolean consolePrint = "java.io.PrintStream".equals(ownerName)
                            && methodName.toLowerCase(Locale.ROOT).startsWith("print");
                    if (consolePrint || "printStackTrace".equals(methodName)) {
                        events.add(SimpleConditionEvent.violated(call,
                                "生产代码存在未受控日志调用: " + call.getDescription()));
                    }
                }
            }
        });
    }

    @Test
    void productionCodeShouldNotDeclareSqlInMyBatisAnnotations() {
        productionClassesRule(new ArchCondition<JavaClass>("不在 Java 注解中声明原生 SQL") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass targetClass = dependency.getTargetClass();
                    if (!"org.apache.ibatis.annotations".equals(targetClass.getPackageName())) {
                        continue;
                    }
                    String annotationName = targetClass.getSimpleName();
                    boolean sqlAnnotation = "Select".equals(annotationName)
                            || "Insert".equals(annotationName)
                            || "Update".equals(annotationName)
                            || "Delete".equals(annotationName)
                            || annotationName.endsWith("Provider");
                    if (sqlAnnotation) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                "生产代码禁止使用 MyBatis SQL 注解: " + item.getName()));
                    }
                }
            }
        });
    }

    @Test
    void productionJavaSourceShouldNotContainRawSqlStringLiterals() throws IOException {
        Pattern rawSqlLiteralPattern = Pattern.compile(
                "\"\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|REPLACE|TRUNCATE|ALTER|CREATE|DROP)\\b",
                Pattern.CASE_INSENSITIVE);
        List<Path> javaSourcePathList;
        try (Stream<Path> sourcePathStream = Files.walk(Paths.get("src/main/java"))) {
            javaSourcePathList = sourcePathStream
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }

        List<String> violationList = new ArrayList<String>();
        for (Path javaSourcePath : javaSourcePathList) {
            String sourceText = new String(Files.readAllBytes(javaSourcePath), StandardCharsets.UTF_8);
            if (rawSqlLiteralPattern.matcher(sourceText).find()) {
                violationList.add(javaSourcePath.toString());
            }
        }
        assertTrue(violationList.isEmpty(), "生产 Java 文件中发现原生 SQL 字符串: " + violationList);
    }

    @Test
    void productionImagePathShouldNotCallImageIoRead() {
        productionClassesRule(new ArchCondition<JavaClass>("不调用 ImageIO.read 整图解码") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if ("javax.imageio.ImageIO".equals(call.getTarget().getOwner().getFullName())
                            && "read".equals(call.getTarget().getName())) {
                        events.add(SimpleConditionEvent.violated(call,
                                "图片路径禁止 ImageIO.read: " + call.getDescription()));
                    }
                }
            }
        });
    }

    @Test
    void onlyDishTagTaggingBoundaryShouldDependOnDishTagModelClient() {
        productionClassesRule(new ArchCondition<JavaClass>("只允许 LLM-B 打标边界依赖 DishTagModelClient") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    if (!dependency.getTargetClass().isAssignableTo(DishTagModelClient.class)) {
                        continue;
                    }
                    boolean allowed = item.getPackageName().contains(".llm.dishtag")
                            || item.getPackageName().equals("com.example.healthreport.infra");
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                "非 LLM-B 类依赖了 DishTagModelClient: " + item.getName()));
                    }
                }
            }
        });
    }

    /** 对全部生产包执行同一个字节码条件。 */
    private void productionClassesRule(ArchCondition<JavaClass> condition) {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.healthreport");
        classes().should(condition).check(productionClasses);
    }
}
