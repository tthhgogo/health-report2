package com.example.healthreport.constants;

/**
 * 展示类内容版本。
 * <p>
 * 改动摘要文案、摄入量说明、搭配贴士、烹饪与行为建议、仅展示食材时 bump 本值。
 * </p>
 * <p>
 * <b>本值不进 tagHash，不触发重打标</b>——把两个版本合成一个的话， 改一句展示文案也会触发全部 22 个维度全量重打标。
 * </p>
 */
public final class DisplayContentVersion {

	/** 2026-08-27 全量营养与饮食内容裁决后的首个发布候选版本。 */
	public static final String VALUE = "display-1.0.0";

	private DisplayContentVersion() {
	}

}
