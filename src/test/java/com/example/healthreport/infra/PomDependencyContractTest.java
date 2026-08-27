package com.example.healthreport.infra;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven 生产与测试依赖坐标的可复现性契约测试。
 */
class PomDependencyContractTest {

    /**
     * groupId 与 artifactId 必须写死，只有版本允许引用已声明的版本属性。
     */
    @Test
    void dependencyPublishersAndArtifactsShouldNotBeReplaceableAtBuildTime() throws Exception {
        Document document = parsePom();
        Element propertiesElement = firstElement(document, "properties");
        NodeList dependencyNodeList = document.getElementsByTagName("dependency");
        boolean tinyPinyinFound = false;

        for (int index = 0; index < dependencyNodeList.getLength(); index++) {
            Element dependencyElement = (Element) dependencyNodeList.item(index);
            String groupId = requiredChildText(dependencyElement, "groupId");
            String artifactId = requiredChildText(dependencyElement, "artifactId");
            assertFalse(groupId.contains("${"), "dependency groupId 不得使用属性插值");
            assertFalse(artifactId.contains("${"), "dependency artifactId 不得使用属性插值");

            String version = optionalChildText(dependencyElement, "version");
            if (version != null && version.contains("${")) {
                assertTrue(version.startsWith("${") && version.endsWith("}"),
                        "版本属性必须是完整占位符，不得与其他文本拼接");
                String propertyName = version.substring(2, version.length() - 1);
                assertFalse(propertyName.contains("${") || propertyName.contains("}"),
                        "版本只能引用一个属性");
                assertTrue(propertyName.endsWith(".version"), "依赖属性插值只能引用版本属性");
                String propertyValue = requiredChildText(propertiesElement, propertyName);
                assertFalse(propertyValue.contains("${"), "版本属性必须解析为固定字面量");
            }

            if ("com.github.promeg".equals(groupId) && "tinypinyin".equals(artifactId)) {
                tinyPinyinFound = true;
                assertEquals("${tinypinyin.version}", version);
            }
        }

        assertTrue(tinyPinyinFound, "必须声明 com.github.promeg:tinypinyin 这一固定坐标");
    }

    private static Document parsePom() throws Exception {
        Path pomPath = Paths.get("pom.xml");
        assertTrue(Files.isRegularFile(pomPath), "项目根目录必须存在 pom.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(pomPath.toFile());
    }

    private static Element firstElement(Document document, String tagName) {
        NodeList nodeList = document.getElementsByTagName(tagName);
        assertTrue(nodeList.getLength() > 0, "pom.xml 缺少 " + tagName);
        return (Element) nodeList.item(0);
    }

    private static String requiredChildText(Element parentElement, String childName) {
        String childText = optionalChildText(parentElement, childName);
        assertNotNull(childText, "pom.xml 缺少 " + childName);
        assertFalse(childText.isEmpty(), "pom.xml 中 " + childName + " 不得为空");
        return childText;
    }

    private static String optionalChildText(Element parentElement, String childName) {
        NodeList childNodeList = parentElement.getChildNodes();
        for (int index = 0; index < childNodeList.getLength(); index++) {
            Node childNode = childNodeList.item(index);
            if (childNode.getNodeType() == Node.ELEMENT_NODE && childName.equals(childNode.getNodeName())) {
                return childNode.getTextContent().trim();
            }
        }
        return null;
    }
}
