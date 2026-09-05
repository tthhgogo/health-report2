package com.example.healthreport.task;

/**
 * 归属上下文（userId / companyId）的入口断言。
 * <p>上游认证系统提供的标识没有长度契约，而三张 ct_ 表的对应列均为 VARCHAR(64)；
 * 超长值必须在入口拒绝，不能等到 insert 时以 SQL 异常的形式变成 500。</p>
 */
final class OwnerContext {

	/** 与 ct_health_report_task / ct_health_report_file / ct_dish_tag 的 company_id、user_id 列宽一致。 */
	static final int OWNER_ID_MAX_LENGTH = 64;

	private OwnerContext() {
	}

	/** 断言用户与企业标识非空且不超过数据库列宽。 */
	static void assertValid(String userId, String companyId) {
		if (userId == null || userId.length() == 0 || companyId == null || companyId.length() == 0) {
			throw new IllegalArgumentException("用户与企业归属不能为空");
		}
		if (userId.length() > OWNER_ID_MAX_LENGTH || companyId.length() > OWNER_ID_MAX_LENGTH) {
			throw new IllegalArgumentException("用户或企业标识超过数据库列宽" + OWNER_ID_MAX_LENGTH);
		}
	}

}
