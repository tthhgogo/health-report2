package com.example.healthreport.infra;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 当日在架菜品所属企业的 Keyset 分页结果。 */
@Getter
public final class CompanyCursorPage {

	private final List<String> companyIdList;

	private final String lastCompanyId;

	/** 创建企业游标页；严格顺序与游标前进由查询边界统一校验。 */
	public CompanyCursorPage(List<String> companyIdList, String lastCompanyId) {
		if (companyIdList == null) {
			throw new IllegalArgumentException("企业分页列表不能为空");
		}
		this.companyIdList = Collections.unmodifiableList(new ArrayList<String>(companyIdList));
		this.lastCompanyId = lastCompanyId;
	}

}
