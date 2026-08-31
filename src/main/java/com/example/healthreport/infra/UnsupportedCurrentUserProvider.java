package com.example.healthreport.infra;

import org.springframework.stereotype.Component;

/**
 * 认证上下文尚未接入时的显式失败占位 Bean。
 * <p>
 * 接入真实实现时删除本占位 Bean；当前绝不伪造用户标识。
 * </p>
 */
@Component
class UnsupportedCurrentUserProvider implements CurrentUserProvider {

	// currentUserId/currentCompanyId 沿用接口中的 TODO 显式失败实现。

}
