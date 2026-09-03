package com.example.healthreport.render;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 解析层不得退回文本行聚类或坐标聚类的架构红线。 */
class ParsingArchitectureTest {

    @Test
    void parsePackageShouldNotDependOnTextStripperOrExposeCoordinateClusteringMethods() {
        JavaClasses parseClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.healthreport.render");
        ArchRule noTextStripper = noClasses().that().resideInAPackage("..render..")
                .should().dependOnClassesThat().areAssignableTo(PDFTextStripper.class);
        noTextStripper.check(parseClasses);

        ArchRule noClusteringMethods = classes().that().resideInAPackage("..render..")
                .should(new ArchCondition<JavaClass>("不声明坐标聚类或拼行方法") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaMethod method : item.getMethods()) {
                            String name = method.getName().toLowerCase(Locale.ROOT);
                            boolean forbidden = name.contains("cluster") || name.contains("groupline")
                                    || name.contains("mergecell") || name.contains("mergeline");
                            if (forbidden) {
                                events.add(SimpleConditionEvent.violated(method,
                                        "解析层存在被禁止的坐标聚类/拼行方法: " + method.getFullName()));
                            }
                        }
                    }
                });
        noClusteringMethods.check(parseClasses);
    }
}
