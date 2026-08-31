package com.example.healthreport.cache;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 按企业、业务日、标签大类与方向生成菜品推荐 SET Key。 */
@Component
public class DishRecommendSetKeyFactory {

	/** 菜品方向集合统一前缀；本缓存不创建 active 或 all 辅助 Key。 */
	private static final String KEY_PREFIX = "dish:recommend:";

	private final CompanyRedisKeyCodec companyRedisKeyCodec;

	public DishRecommendSetKeyFactory(CompanyRedisKeyCodec companyRedisKeyCodec) {
		this.companyRedisKeyCodec = companyRedisKeyCodec;
	}

	/** 生成在线正式 SET Key。 */
	public String formalKey(String companyId, LocalDate bizDate, DishTagSetRef setRef) {
		return prefix(companyId, bizDate) + setRef.getCategory().getKeySegment() + ":"
				+ setRef.getDirection().getKeySegment() + ":" + setRef.getEnumKey();
	}

	/** 生成与正式 Key 同 slot 的构建 SET Key。 */
	public String stagingKey(String companyId, LocalDate bizDate, String buildId, DishTagSetRef setRef) {
		if (buildId == null || buildId.length() == 0 || buildId.indexOf(':') >= 0) {
			throw new IllegalArgumentException("构建ID不能为空且不能包含冒号");
		}
		return prefix(companyId, bizDate) + "build:" + buildId + ":" + setRef.getCategory().getKeySegment() + ":"
				+ setRef.getDirection().getKeySegment() + ":" + setRef.getEnumKey();
	}

	private String prefix(String companyId, LocalDate bizDate) {
		if (bizDate == null) {
			throw new IllegalArgumentException("业务日不能为空");
		}
		String encodedCompanyId = companyRedisKeyCodec.encode(companyId);
		return KEY_PREFIX + "{" + encodedCompanyId + ":" + bizDate + "}:";
	}

}
