package com.example.healthreport.support;

/**
 * 体检报告分析失败码。
 * <p>只定义失败原因，不在此层绑定页面文案。</p>
 */
public enum FailCode {

    /** 上传文件的真实格式不在支持范围内。 */
    UNSUPPORTED_FORMAT,

    /** 上传字节数、图片像素或任务累计字节数超过限制。 */
    FILE_TOO_LARGE,

    /** 文件结构损坏、为空或无法通过格式可读性校验。 */
    FILE_UNREADABLE,

    /** multipart 报文无法解析，区别于字节超限的 FILE_TOO_LARGE。 */
    MALFORMED_REQUEST,

    /** 文件已被另一个仍然有效的任务绑定。 */
    FILE_ALREADY_BOUND,

    /** 文件在创建或绑定任务前已经过期。 */
    FILE_EXPIRED,

    /** 上传预筛或工作线程精确计算发现页数、等效页数超限。 */
    PAGE_LIMIT_EXCEEDED,

    /** 结果接口在任务尚未成功时被调用。 */
    TASK_NOT_FINISHED,

    /** 全部可读文件均未识别出体检报告特征。 */
    NOT_HEALTH_REPORT,

    /** 报告页面无法渲染，或模型判定整份图像不可读。 */
    UNREADABLE,

    /** 图像无法在已确认的容量与清晰度约束下处理。 */
    IMAGE_TOO_LARGE,

    /** 多文件中通过来源校验的人员信息存在冲突。 */
    IDENTITY_MISMATCH,

    /** 任务超过硬截止时间。 */
    EXECUTION_TIMEOUT,

    /** 服务端集成、模型契约或内部执行失败。 */
    SERVER_ERROR,

    /** 任务不存在、无权访问、已删除、已过期或结果缓存已失效。 */
    RESULT_EXPIRED
}
