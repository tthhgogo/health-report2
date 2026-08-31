package com.example.healthreport.cache;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 企业标识 Redis Key 编码器，避免外部标识破坏 Cluster hash tag 边界。 */
@Component
public class CompanyRedisKeyCodec {

	/** 将企业标识编码为 UTF-8 Base64URL 无填充文本。 */
	public String encode(String companyId) {
		if (companyId == null || companyId.length() == 0) {
			throw new IllegalArgumentException("企业ID不能为空");
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(companyId.getBytes(StandardCharsets.UTF_8));
	}

}
