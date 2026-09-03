package com.example.healthreport.llm.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 调用一（健康指标）的已校验结果。
 * <p>字段与 {@code schema/indicators.schema.json} 一一对应；本类不携带任何校验逻辑，
 * Schema 与结构校验完成后才允许构造。{@code patients} 仅供同一性校验，用完即弃，
 * 不得进入最终结果、Redis 或普通日志。</p>
 */
@Getter
public final class IndicatorsResult {

    private final String reportStatus;
    private final List<Patient> patients;
    private final Overview overview;
    private final List<Section> sections;

    @JsonCreator
    public IndicatorsResult(@JsonProperty("reportStatus") String reportStatus,
                            @JsonProperty("patients") List<Patient> patients,
                            @JsonProperty("overview") Overview overview,
                            @JsonProperty("sections") List<Section> sections) {
        this.reportStatus = reportStatus;
        this.patients = patients == null
                ? Collections.<Patient>emptyList() : Collections.unmodifiableList(patients);
        this.overview = overview;
        this.sections = sections == null
                ? Collections.<Section>emptyList() : Collections.unmodifiableList(sections);
    }

    /** 返回不含 patients 的副本；同一性校验完成后由编排器立即调用。 */
    public IndicatorsResult withoutPatients() {
        return new IndicatorsResult(reportStatus, null, overview, sections);
    }

    /** 顶部总览条数字，直接采信不交叉核对（设计方案 §5.4）。 */
    @Getter
    public static final class Overview {

        private final int totalCount;
        private final int abnormalCount;
        private final String source;

        @JsonCreator
        public Overview(@JsonProperty("totalCount") int totalCount,
                        @JsonProperty("abnormalCount") int abnormalCount,
                        @JsonProperty("source") String source) {
            this.totalCount = totalCount;
            this.abnormalCount = abnormalCount;
            this.source = source;
        }
    }

    /** 一个章节及其指标；数组顺序即展示顺序。 */
    @Getter
    public static final class Section {

        private final String section;
        private final int page;
        private final List<Indicator> indicators;

        @JsonCreator
        public Section(@JsonProperty("section") String section,
                       @JsonProperty("page") int page,
                       @JsonProperty("indicators") List<Indicator> indicators) {
            this.section = section;
            this.page = page;
            this.indicators = indicators == null
                    ? Collections.<Indicator>emptyList() : Collections.unmodifiableList(indicators);
        }
    }

    /** 单条指标的六个字段。 */
    @Getter
    public static final class Indicator {

        private final String name;
        private final String value;
        private final String unit;
        private final String refRange;
        private final boolean conclusionGenerated;
        private final IndicatorStatus status;

        @JsonCreator
        public Indicator(@JsonProperty("name") String name,
                         @JsonProperty("value") String value,
                         @JsonProperty("unit") String unit,
                         @JsonProperty("refRange") String refRange,
                         @JsonProperty("conclusionGenerated") boolean conclusionGenerated,
                         @JsonProperty("status") IndicatorStatus status) {
            this.name = name;
            this.value = value;
            this.unit = unit;
            this.refRange = refRange;
            this.conclusionGenerated = conclusionGenerated;
            this.status = status;
        }
    }

    /** 临时患者身份字段；只在工作线程内存中用于同一性比对。 */
    @Getter
    public static final class Patient {

        private final int page;
        private final String name;
        private final String gender;

        @JsonCreator
        public Patient(@JsonProperty("page") int page,
                       @JsonProperty("name") String name,
                       @JsonProperty("gender") String gender) {
            this.page = page;
            this.name = name;
            this.gender = gender;
        }
    }
}
