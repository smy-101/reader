package com.smy101.reader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 临时用例:验证 CI 红灯拦截能力(M0-06 红→绿验证),下一个提交即回滚。 */
class TempRedTest {

    @Test
    void 故意失败_验证CI拦截() {
        assertThat(false).as("CI 应当变红拦截此提交").isTrue();
    }
}
