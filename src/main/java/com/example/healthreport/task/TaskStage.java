package com.example.healthreport.task;

/**
 * 前端展示的三阶段进度标识。
 */
public enum TaskStage {

    /** 上传完成、等待分析线程。 */
    UPLOADING,

    /** 文件解析与内容识别。 */
    PARSING,

    /** 分析结果组装。 */
    ASSEMBLING
}
