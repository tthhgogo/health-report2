package com.example.healthreport.dish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** 缓存 Value 的字段名固定为 {@code {verdict, matchedIngredients}}，改名会让已写入的缓存读不出来。 */
class TagValueJsonTest {

    @Test
    void cacheJsonShouldUseContractNamesAndRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TagValue source = new TagValue(TagState.REJECT, Collections.singletonList("食材"));

        String json = mapper.writeValueAsString(source);
        JsonNode rootNode = mapper.readTree(json);

        assertThat(rootNode.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("verdict", "matchedIngredients");
        assertThat(mapper.readValue(json, TagValue.class).getState()).isEqualTo(TagState.REJECT);
    }
}
