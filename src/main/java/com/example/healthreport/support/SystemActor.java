package com.example.healthreport.support;

/**
 * 数据库审计列使用的固定系统身份。
 * <p>审计身份与归属用户完全分离，严禁把用户标识写入 create_by 或 update_by。</p>
 */
public final class SystemActor {

    /** 在线上传、建任务与接口写入。 */
    public static final String HEALTH_REPORT_API = "HEALTH_REPORT_API";

    /** 体检报告分析工作线程写回。 */
    public static final String HEALTH_REPORT_WORKER = "HEALTH_REPORT_WORKER";

    /** 离线菜品打标任务写入。 */
    public static final String DISH_TAG_JOB = "DISH_TAG_JOB";

    private SystemActor() {
    }
}
