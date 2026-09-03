package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dishrecommend.DishNameSorter;
import com.example.healthreport.llm.extraction.DietTagsResult;
import com.example.healthreport.safety.HighRiskAdviceGate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 排序唯一性和模块三结构隔离的架构回归。 */
class DisplayArchitectureTest {

    /** 生产展示代码根目录，用于静态检查比较器只能存在于唯一排序器。 */
    private static final Path ASSEMBLE_SOURCE_ROOT = Paths.get("src/main/java/com/example/healthreport/assemble");

    @Test
    void comparatorsShouldExistOnlyInDishNameSorter() throws IOException {
        List<Path> javaPathList;
        try (Stream<Path> pathStream = Files.walk(ASSEMBLE_SOURCE_ROOT)) {
            javaPathList = pathStream.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        for (Path javaPath : javaPathList) {
            if (!javaPath.endsWith("DishNameSorter.java")) {
                String content = new String(Files.readAllBytes(javaPath), StandardCharsets.UTF_8);
                assertThat(content).as("展示模块不得自行实现比较器：%s", javaPath)
                        .doesNotContain("Comparator<")
                        .doesNotContain("Collections.sort(")
                        .doesNotContain(".sorted(");
            }
        }
    }

    @Test
    void moduleThreeAssemblerShouldOnlyAcceptIsolatedInput() throws NoSuchMethodException {
        assertThat(DietAdviceAssembler.class.getMethod("assemble", DietTagsResult.class)).isNotNull();
        assertThat(DietAdviceAssembler.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getName().contains("ValidatedExtractionOutput"));
        assertThat(HighRiskAdviceGate.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("shouldSuppress"));
        assertThat(DishNameSorter.class).isNotNull();
    }
}
