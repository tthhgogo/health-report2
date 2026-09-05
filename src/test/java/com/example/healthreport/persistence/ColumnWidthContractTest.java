package com.example.healthreport.persistence;

import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.constants.ResultSchemaVersion;
import com.example.healthreport.constants.TagRuleVersion;
import com.example.healthreport.dish.TagState;
import com.example.healthreport.render.ContentType;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.PartialReason;
import com.example.healthreport.support.SystemActor;
import com.example.healthreport.task.FileStatus;
import com.example.healthreport.task.TaskStage;
import com.example.healthreport.task.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码写入值与 DDL 列宽的契约：枚举名与版本常量是最容易在加值时悄悄超限的写入源，
 * 超限在生产端表现为 insert 抛 SQL 异常变 500，而不是干净的业务错误。
 * <p>列宽真源见 {@code sql/schema.sql}；改列宽时同步这里的上限。</p>
 */
class ColumnWidthContractTest {

	@Test
	void taskTableEnumNamesShouldFitColumnWidths() {
		for (TaskStatus status : TaskStatus.values()) {
			assertThat(status.name().length()).as("status VARCHAR(16)：%s", status).isLessThanOrEqualTo(16);
		}
		for (TaskStage stage : TaskStage.values()) {
			assertThat(stage.name().length()).as("stage VARCHAR(16)：%s", stage).isLessThanOrEqualTo(16);
		}
		for (FailCode failCode : FailCode.values()) {
			assertThat(failCode.name().length()).as("fail_code VARCHAR(32)：%s", failCode).isLessThanOrEqualTo(32);
		}
		for (PartialReason reason : PartialReason.values()) {
			assertThat(reason.name().length()).as("partial_reason VARCHAR(32)：%s", reason).isLessThanOrEqualTo(32);
		}
	}

	@Test
	void fileTableEnumNamesShouldFitColumnWidths() {
		for (FileStatus status : FileStatus.values()) {
			assertThat(status.name().length()).as("status VARCHAR(16)：%s", status).isLessThanOrEqualTo(16);
		}
		for (ContentType contentType : ContentType.values()) {
			assertThat(contentType.name().length()).as("content_type VARCHAR(64)：%s", contentType)
				.isLessThanOrEqualTo(64);
			// display_name = 「体检报告-」(5) + fileId 前 8 位 + 「.」(1) + 小写扩展名，须落在 VARCHAR(64) 内。
			assertThat(5 + 8 + 1 + contentType.name().length()).as("display_name VARCHAR(64)：%s", contentType)
				.isLessThanOrEqualTo(64);
		}
	}

	@Test
	void dishTagTableEnumNamesAndVersionsShouldFitColumnWidths() {
		for (TagState state : TagState.values()) {
			assertThat(state.name().length()).as("verdict VARCHAR(12)：%s", state).isLessThanOrEqualTo(12);
		}
		for (AllergenKey key : AllergenKey.values()) {
			assertThat(key.name().length()).as("enum_key VARCHAR(32)：%s", key).isLessThanOrEqualTo(32);
		}
		for (DietRequirementKey key : DietRequirementKey.values()) {
			assertThat(key.name().length()).as("enum_key VARCHAR(32)：%s", key).isLessThanOrEqualTo(32);
		}
		for (NutritionKey key : NutritionKey.values()) {
			assertThat(key.name().length()).as("enum_key VARCHAR(32)：%s", key).isLessThanOrEqualTo(32);
		}
		assertThat(PromptVersions.INDICATORS.length()).isLessThanOrEqualTo(32);
		assertThat(PromptVersions.PROBLEMS.length()).isLessThanOrEqualTo(32);
		assertThat(PromptVersions.DIET_TAGS.length()).isLessThanOrEqualTo(32);
		assertThat(PromptVersions.DISH_TAG.length()).as("prompt_version VARCHAR(32)").isLessThanOrEqualTo(32);
		assertThat(TagRuleVersion.VALUE.length()).as("tag_rule_version VARCHAR(32)").isLessThanOrEqualTo(32);
		// 不落库，但进 Redis key：约束住避免 key 失控膨胀。
		assertThat(ResultSchemaVersion.VALUE.length()).isLessThanOrEqualTo(16);
	}

	@Test
	void auditActorIdentifiersShouldFitColumnWidths() {
		assertThat(SystemActor.HEALTH_REPORT_API.length()).as("create_by/update_by VARCHAR(50)")
			.isLessThanOrEqualTo(50);
		assertThat(SystemActor.HEALTH_REPORT_WORKER.length()).isLessThanOrEqualTo(50);
		assertThat(SystemActor.DISH_TAG_JOB.length()).isLessThanOrEqualTo(50);
	}

}
