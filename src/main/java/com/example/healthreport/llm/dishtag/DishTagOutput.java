package com.example.healthreport.llm.dishtag;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** LLM-B 紧凑三态响应 DTO；跨列表覆盖与互斥由 Java 另行校验。 */
@Data
public class DishTagOutput {

    private String enumKey;
    private List<Long> neutralDishIds = new ArrayList<Long>();
    private List<Long> unknownDishIds = new ArrayList<Long>();
    private List<Hit> hitList = new ArrayList<Hit>();

    /** 仅 REJECT 项携带的证据。 */
    @Data
    public static class Hit {
        private Long dishId;
        private String verdict;
        private String evidenceType;
        private List<String> matchedIngredients = new ArrayList<String>();
        private String reason;
    }
}
