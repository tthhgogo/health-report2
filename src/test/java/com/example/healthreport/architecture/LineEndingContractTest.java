package com.example.healthreport.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 源文件与 MyBatis 映射 XML 一律 CRLF 行尾（`AGENTS.md` §行尾、`.gitattributes`）。
 *
 * <p><b>为什么需要一条测试而不是靠人记。</b> 2026-09-02 连续三轮出现文件退回 LF，
 * 原因有两条、都不显眼：</p>
 *
 * <ul>
 *   <li>用 {@code git status --porcelain} 找改动文件时，<b>未跟踪的目录只显示为一条目录记录</b>
 *       （{@code ?? src/.../llm/schema/}），里面的文件根本不会被遍历到；</li>
 *   <li>脚本以文本模式读写源文件时，{@code \r\n} 在读取阶段就被规范化成 {@code \n}，
 *       <b>每一次编辑都在悄悄退回</b>，而编译与测试全都照常通过。</li>
 * </ul>
 *
 * <p>所以这里<b>全量扫描目录树</b>，不依赖任何版本控制状态。</p>
 */
class LineEndingContractTest {

    @Test
    void javaAndMapperFilesMustUseCrlf() throws IOException {
        List<String> offenderList = new ArrayList<String>();
        collectOffenders(Paths.get("src"), ".java", offenderList);
        collectOffenders(Paths.get("src", "main", "resources", "mapper"), ".xml", offenderList);

        assertThat(offenderList)
                .as("以下文件不是 CRLF 行尾，违反 AGENTS.md 与 .gitattributes")
                .isEmpty();
    }

    /** 只要文件里出现过一次 {@code \r\n} 就认为是 CRLF；空文件不参与判定。 */
    private void collectOffenders(Path root, String suffix, List<String> offenderList)
            throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!file.getFileName().toString().endsWith(suffix)) {
                    return FileVisitResult.CONTINUE;
                }
                byte[] content = Files.readAllBytes(file);
                if (content.length > 0 && indexOfCrlf(content) < 0) {
                    offenderList.add(file.toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private int indexOfCrlf(byte[] content) {
        for (int index = 0; index + 1 < content.length; index++) {
            if (content[index] == '\r' && content[index + 1] == '\n') {
                return index;
            }
        }
        return -1;
    }
}
