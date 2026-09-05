package com.example.healthreport.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 归属标识入口断言：空值与超过 VARCHAR(64) 列宽都必须在入口拒绝，不能等 insert 报 SQL 异常。 */
class OwnerContextTest {

	@Test
	void blankOwnerIdentifiersShouldBeRejected() {
		assertThatThrownBy(() -> OwnerContext.assertValid(null, "company-a"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OwnerContext.assertValid("user-1", ""))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void identifiersUpToColumnWidthShouldPassAndLongerShouldBeRejected() {
		String sixtyFour = repeat('a', 64);
		assertThatCode(() -> OwnerContext.assertValid(sixtyFour, sixtyFour)).doesNotThrowAnyException();

		String sixtyFive = repeat('a', 65);
		assertThatThrownBy(() -> OwnerContext.assertValid(sixtyFive, "company-a"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OwnerContext.assertValid("user-1", sixtyFive))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private String repeat(char character, int count) {
		StringBuilder builder = new StringBuilder(count);
		for (int index = 0; index < count; index++) {
			builder.append(character);
		}
		return builder.toString();
	}

}
