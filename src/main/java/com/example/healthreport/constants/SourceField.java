package com.example.healthreport.constants;

/**
 * 过敏例外的作用字段。
 */
public enum SourceField {

    /** 只取消菜名中该字面子串的命中。食材表里的明确命中永远优先，不可被例外覆盖 */
    DISH_NAME;
}
