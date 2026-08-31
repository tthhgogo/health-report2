package com.example.healthreport.infra;

/**
 * 当前认证用户标识提供边界。
 */
public interface CurrentUserProvider {

	/** TODO 接入上游认证上下文。 */
	default String currentUserId() {
		throw new UnsupportedOperationException("CurrentUserProvider尚未实现");
	}

	/** TODO 接入上游认证上下文中的当前企业标识。 */
	default String currentCompanyId() {
		throw new UnsupportedOperationException("CurrentUserProvider尚未实现");
	}

}
