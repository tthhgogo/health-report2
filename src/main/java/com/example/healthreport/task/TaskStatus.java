package com.example.healthreport.task;

/**
 * 体检报告分析任务状态。
 */
public enum TaskStatus {

    /** 等待工作线程领取。 */
    QUEUED,

    /** 文件解析中。 */
    PARSING,

    /** 模型抽取中。 */
    EXTRACTING,

    /** 四模块组装中。 */
    ASSEMBLING,

    /** 任务成功。 */
    SUCCEEDED,

    /** 任务失败。 */
    FAILED
}
