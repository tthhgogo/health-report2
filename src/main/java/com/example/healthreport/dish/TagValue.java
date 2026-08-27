package com.example.healthreport.dish;

import lombok.Getter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 缓存与在线裁决共用的菜品标签值。 */
@Getter
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class TagValue {

    @JsonProperty("verdict")
    private final TagState state;
    @JsonProperty("matchedIngredients")
    private final List<String> matchedIngredientList;

    /** 创建不可变标签值。 */
    @JsonCreator
    public TagValue(@JsonProperty("verdict") TagState state,
                    @JsonProperty("matchedIngredients") List<String> matchedIngredientList) {
        if (state == null || matchedIngredientList == null) {
            throw new IllegalArgumentException("标签值不能为空");
        }
        this.state = state;
        this.matchedIngredientList = Collections.unmodifiableList(
                new ArrayList<String>(matchedIngredientList));
    }

    /** 创建无证据食材的状态值。 */
    public static TagValue of(TagState state) {
        return new TagValue(state, Collections.<String>emptyList());
    }
}
