package com.example.healthreport.support;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * 任务与文件 ID 的唯一生成及入口断言实现。
 * <p>数据库排序规则大小写不敏感，因此入口只接受标准小写 UUID，绝不静默修正。</p>
 */
@Component
public class IdCanonicalizer {

    /**
     * 生成小写标准 UUID 任务 ID。
     *
     * @return 带连字符的三十六位小写 UUID
     */
    public String newTaskId() {
        return newCanonicalId();
    }

    /**
     * 生成小写标准 UUID 文件 ID。
     *
     * @return 带连字符的三十六位小写 UUID
     */
    public String newFileId() {
        return newCanonicalId();
    }

    /**
     * 断言输入已经是小写标准 UUID。
     * <p>本方法不 trim、不补连字符、不转小写；任何非规范形式都直接拒绝。</p>
     *
     * @param value 外部传入的任务或文件 ID
     * @return 未经修改的原值
     * @throws IllegalArgumentException 输入为空、含大写或不是标准 UUID 时抛出
     */
    public String canonicalize(String value) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("ID不能为空");
        }
        String lowerCaseValue = value.toLowerCase(Locale.ROOT);
        if (!lowerCaseValue.equals(value)) {
            throw new IllegalArgumentException("ID必须使用小写规范形式");
        }
        final String parsedValue;
        try {
            parsedValue = UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("ID不是标准UUID", exception);
        }
        if (!parsedValue.equals(value)) {
            throw new IllegalArgumentException("ID不是标准UUID规范形式");
        }
        return value;
    }

    private String newCanonicalId() {
        return UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }
}
