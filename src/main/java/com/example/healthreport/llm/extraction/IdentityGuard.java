package com.example.healthreport.llm.extraction;

import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.text.TextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多文件同一性校验（设计方案 §4.5）。
 *
 * <p>模型只报读到的姓名性别，判定权在 Java：姓名去空格后精确相等、性别精确相等；
 * 任一明确冲突即 {@code IDENTITY_MISMATCH}。全部缺失、仅一份有值、同名不同人是已知盲区，
 * 不猜测、不因缺失误报冲突。日志不得出现姓名与性别的值。</p>
 */
@Slf4j
@Component
public class IdentityGuard {

    private final TextNormalizer textNormalizer = new TextNormalizer();

    /** 校验后调用方必须立即丢弃 patients（{@code IndicatorsResult#withoutPatients}）。 */
    public void check(List<IndicatorsResult.Patient> patientList, PageImageSequence images) {
        if (patientList == null || patientList.isEmpty()) {
            return;
        }
        Map<Integer, String> nameByFile = new LinkedHashMap<Integer, String>();
        Map<Integer, String> genderByFile = new LinkedHashMap<Integer, String>();
        for (IndicatorsResult.Patient patient : patientList) {
            int fileIndex = images.locate(patient.getPage()).getFileIndex();
            String name = stripped(patient.getName());
            String gender = stripped(patient.getGender());
            if (name != null) {
                assertConsistent(nameByFile, fileIndex, name, "姓名");
            }
            if (gender != null) {
                assertConsistent(genderByFile, fileIndex, gender, "性别");
            }
        }
        assertCrossFileConsistent(nameByFile, "姓名");
        assertCrossFileConsistent(genderByFile, "性别");
    }

    /** 同一文件内两处身份信息互相矛盾同样是明确冲突。 */
    private void assertConsistent(Map<Integer, String> valueByFile, int fileIndex,
                                  String value, String fieldLabel) {
        String known = valueByFile.get(Integer.valueOf(fileIndex));
        if (known == null) {
            valueByFile.put(Integer.valueOf(fileIndex), value);
            return;
        }
        if (!known.equals(value)) {
            log.warn("多文件同一性校验发现冲突，字段={}，fileIndex={}", fieldLabel, fileIndex);
            throw new HealthReportException(FailCode.IDENTITY_MISMATCH, 400);
        }
    }

    private void assertCrossFileConsistent(Map<Integer, String> valueByFile, String fieldLabel) {
        String reference = null;
        for (Map.Entry<Integer, String> entry : valueByFile.entrySet()) {
            if (reference == null) {
                reference = entry.getValue();
                continue;
            }
            if (!reference.equals(entry.getValue())) {
                log.warn("多文件同一性校验发现跨文件冲突，字段={}", fieldLabel);
                throw new HealthReportException(FailCode.IDENTITY_MISMATCH, 400);
            }
        }
    }

    /**
     * 规范化后去全部空白再比较；空串视同缺失。
     * <p>必须先过 {@link TextNormalizer}（开发方案 §5.2 的消费者表）：
     * 全角空格 U+3000、NBSP 不在 Java 默认 {@code \\s} 里，「张　三」与「张三」
     * 不规范化就会被误判成两个人；零宽字符同理。</p>
     */
    private String stripped(String value) {
        if (value == null) {
            return null;
        }
        String strippedValue = textNormalizer.normalize(value)
                .replaceAll("\\s+", "");
        return strippedValue.isEmpty() ? null : strippedValue;
    }
}
