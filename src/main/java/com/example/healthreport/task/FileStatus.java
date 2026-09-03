package com.example.healthreport.task;

/**
 * 上传文件状态。
 */
public enum FileStatus {

    /** 文件已完成上传并等待绑定任务。 */
    UPLOADED,

    /**
     * 清理任务已认领本行，S3 对象即将或正在删除。
     * <p>绑定的条件更新要求 {@code status='UPLOADED'}，进入本状态的行不可能再被绑定——
     * 这是清理与重新分析并发时不误删在用对象的唯一保证（开发方案 §4.5）。</p>
     */
    CLEANING
}
