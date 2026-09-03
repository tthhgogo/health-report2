package com.example.healthreport.constants;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R55c：内容常量版本与结构化摘要的追加式历史契约。 */
class TagRuleVersionHistoryTest {

    @Test
    void latestVersionAndDigestShouldMatchStructuredConstantsWithoutDuplicateMappings()
            throws Exception {
        List<String> lineList = Files.readAllLines(Paths.get("constants/tag-rule-versions.tsv"),
                StandardCharsets.UTF_8);
        assertThat(lineList).isNotEmpty();
        Map<String, String> digestByVersionMap = new HashMap<String, String>();
        Map<String, String> versionByDigestMap = new HashMap<String, String>();
        for (String line : lineList) {
            String[] partArray = line.split("\\t", -1);
            assertThat(partArray).hasSize(2);
            String previousDigest = digestByVersionMap.put(partArray[0], partArray[1]);
            if (previousDigest != null) {
                assertThat(previousDigest).isEqualTo(partArray[1]);
            }
            String previousVersion = versionByDigestMap.put(partArray[1], partArray[0]);
            if (previousVersion != null) {
                assertThat(previousVersion).isEqualTo(partArray[0]);
            }
        }
        String[] latestPartArray = lineList.get(lineList.size() - 1).split("\\t", -1);
        assertThat(latestPartArray[0]).isEqualTo(TagRuleVersion.VALUE);
        assertThat(latestPartArray[1]).isEqualTo(digest());
    }

    private String digest() throws Exception {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<AllergenKey, AllergenGroup> entry : AllergenGroups.ALL.entrySet()) {
            AllergenGroup group = entry.getValue();
            append(builder, "allergen", entry.getKey(), group.getDisplayName(),
                    AllergenGroups.FOOD_BORNE_KEYS.contains(group.getKey()));
            for (AllergenWord word : group.getWordList()) {
                append(builder, word.getMatchWord(), word.getDisplayName(), word.getBucket(),
                        word.getEvidenceLevel(), word.getMatchMode(), word.getReviewStatus());
            }
        }
        for (AllergenExceptions.Rule rule : AllergenExceptions.ALL) {
            append(builder, "exception", rule.getAllergenKey(), rule.getMatchWord(),
                    // SourceField 单值枚举已删除；序列化保留同值字面量以维持摘要稳定（例外恒作用于菜名）。
                    "DISH_NAME", rule.getExceptionPhrase(), rule.getReviewStatus());
        }
        for (Map.Entry<NutritionKey, NutritionRule> entry : NutritionContents.ALL.entrySet()) {
            NutritionRule rule = entry.getValue();
            append(builder, "nutrition", entry.getKey(), rule.getRecommendableFoodList(),
                    rule.getReviewStatus());
        }
        for (Map.Entry<DietRequirementKey, DietRequirementRule> entry
                : DietRequirementContents.ALL.entrySet()) {
            DietRequirementRule rule = entry.getValue();
            append(builder, "diet", entry.getKey(), rule.getAvoidFoodList(),
                    rule.getAvoidDishPatternList(), rule.getCookingTipList(),
                    rule.getReviewStatus(), rule.getPositiveMatchPolicy(),
                    rule.getRecommendableFoodList(), rule.getRecommendTagText(),
                    rule.getPositiveReviewStatus());
        }
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                builder.toString().getBytes(StandardCharsets.UTF_8));
        return String.format("%064x", new BigInteger(1, bytes));
    }

    private void append(StringBuilder builder, Object... valueArray) {
        for (Object value : valueArray) {
            String text = String.valueOf(value);
            builder.append(text.length()).append(':').append(text).append('|');
        }
        builder.append('\n');
    }
}
