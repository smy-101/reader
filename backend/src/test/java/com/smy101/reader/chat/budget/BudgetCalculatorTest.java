package com.smy101.reader.chat.budget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文预算计算器(FR-302,M3 验收标准原文:整书/整章/降级/断尾文案各边界)。
 * 纯函数单测:书内容优先占额 → 剩余装最近对话(丢最旧、不做摘要)→ 降级链 → 三类可读文案。
 */
class BudgetCalculatorTest {

    private static BudgetCalculator.MessageCandidate msg(String role, int tokens) {
        return new BudgetCalculator.MessageCandidate(role, "content-" + tokens, tokens);
    }

    // ---- 书内容槽与降级链 ----

    @Test
    void 整书装得下_模式整书_无降级文案() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 600, 100, null, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.WHOLE_BOOK);
        assertThat(plan.bookContentTokens()).isEqualTo(600);
        assertThat(plan.note()).isNull();
    }

    @Test
    void 整书恰好等于预算_装得下_边界含等号() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 1000, 100, null, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.WHOLE_BOOK);
        assertThat(plan.bookContentTokens()).isEqualTo(1000);
    }

    @Test
    void 整书超限降级为目标章_带降级文案() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 1500, 300, null, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.TARGET_CHAPTER);
        assertThat(plan.bookContentTokens()).isEqualTo(300);
        assertThat(plan.note()).contains("降级").contains("目标章");
    }

    @Test
    void 单章超限且无检索式_错误模式_文案指向换模型或配置embedding() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 5000, 4000, null, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.INSUFFICIENT);
        assertThat(plan.note()).contains("上下文不足").contains("大上下文模型").contains("embedding");
        assertThat(plan.keptMessages()).isEmpty();
    }

    @Test
    void 无目标章且整书超限_同样走不足错误() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 5000, null, null, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.INSUFFICIENT);
    }

    @Test
    void 检索式分支仅由输入标志控制_翻真即达() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 5000, 4000, null, List.of(), true));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.RETRIEVAL);
        assertThat(plan.note()).contains("检索");
        // M3 调用方恒 false:不可达分支的存在只为 M4 预埋(spec · Further Notes)
    }

    // ---- S1 选中文字槽 ----

    @Test
    void 选中文字即书内容槽_不装整书整章() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 5000, 4000, 80, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.SELECTION);
        assertThat(plan.bookContentTokens()).isEqualTo(80);
    }

    @Test
    void 选中文字本身超限_不足错误() {
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                100, 5000, 4000, 800, List.of(), false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.INSUFFICIENT);
        assertThat(plan.note()).contains("上下文不足");
    }

    // ---- 消息装配与断尾 ----

    @Test
    void 剩余额度装最近对话_装不下丢最旧_不做摘要() {
        // 预算 1000,书占 600,剩 400;消息旧→新:300/250/100(共 650)
        // 新→旧装:100(新)+ 250 = 350,再加 300 超额 → 丢最旧的 300
        List<BudgetCalculator.MessageCandidate> messages = List.of(
                msg("user", 300), msg("assistant", 250), msg("user", 100));
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 600, 100, null, messages, false));

        assertThat(plan.keptMessages()).hasSize(2);
        assertThat(plan.keptMessages()).containsExactly(msg("assistant", 250), msg("user", 100)); // 前缀=丢最旧
        assertThat(plan.droppedCount()).isEqualTo(1);
        assertThat(plan.note()).contains("丢弃").contains("1");
    }

    @Test
    void 断尾保留仍是时间前缀_新消息永远在() {
        List<BudgetCalculator.MessageCandidate> messages = List.of(
                msg("user", 900), msg("assistant", 500), msg("user", 50));
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 400, 100, null, messages, false));

        // 剩 600:装 50(新)→ 550;再装 500 超 → 断
        assertThat(plan.keptMessages()).containsExactly(msg("assistant", 500), msg("user", 50));
        assertThat(plan.droppedCount()).isEqualTo(1);
    }

    @Test
    void 全部消息都装得下_不断尾无文案() {
        List<BudgetCalculator.MessageCandidate> messages = List.of(
                msg("user", 100), msg("assistant", 100));
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 600, 100, null, messages, false));

        assertThat(plan.keptMessages()).hasSize(2);
        assertThat(plan.droppedCount()).isZero();
        assertThat(plan.note()).isNull();
    }

    @Test
    void 降级与断尾同时发生_文案合并() {
        List<BudgetCalculator.MessageCandidate> messages = List.of(
                msg("user", 500), msg("assistant", 400), msg("user", 200));
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 1500, 300, null, messages, false));

        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.TARGET_CHAPTER);
        // 剩 700:新→旧装 200+400,最旧的 500 装不下被丢
        assertThat(plan.droppedCount()).isEqualTo(1);
        assertThat(plan.note()).contains("降级").contains("丢弃");
    }

    @Test
    void 消息恰好装满剩余额度_边界含等号() {
        List<BudgetCalculator.MessageCandidate> messages = List.of(
                msg("user", 300), msg("user", 100));
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                1000, 600, 100, null, messages, false));

        assertThat(plan.keptMessages()).hasSize(2);
        assertThat(plan.droppedCount()).isZero();
    }

    // ---- 上限空默认 8k ----

    @Test
    void 上下文上限空按8000保守计() {
        // 整书 7000 < 8000 装得下
        BudgetCalculator.BudgetPlan plan = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                null, 7000, 100, null, List.of(), false));

        assertThat(plan.effectiveLimit()).isEqualTo(8000);
        assertThat(plan.mode()).isEqualTo(BudgetCalculator.Mode.WHOLE_BOOK);

        // 整书 9000 > 8000 → 降级
        BudgetCalculator.BudgetPlan degraded = BudgetCalculator.calculate(new BudgetCalculator.BudgetInput(
                null, 9000, 100, null, List.of(), false));
        assertThat(degraded.mode()).isEqualTo(BudgetCalculator.Mode.TARGET_CHAPTER);
    }
}
