package com.smy101.reader.chat.budget;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文预算计算器(FR-302):纯函数、无 Spring 上下文,M3 验收标准要求的单测 seam。
 * <p>
 * 策略:书内容优先占额(槽位选择 = 降级链)→ 剩余额度装最近对话消息(新→旧装,
 * 装不下丢最旧、<b>不做摘要</b>,保留的一定是时间上连续的最新一段)→
 * 降级/断尾/上下文不足三类可读文案。降级链:整书 → 目标章 → 检索式
 * (检索式仅由输入标志控制,M3 调用方恒 false,M4 翻真);
 * 单章超限且检索式不可用 → INSUFFICIENT 错误模式,文案引导换大上下文模型或配置 embedding。
 * S1 口径:携带选中文字时书内容槽 = 选中文字(已与用户确认),不装整书/整章。
 * 上下文上限空(null)按 8k 保守(D-27)。
 */
public final class BudgetCalculator {

    /** D-27:上下文上限未填的保守缺省。 */
    public static final int DEFAULT_CONTEXT_LIMIT = 8000;

    /** 装配模式:书内容槽的最终形态。 */
    public enum Mode {
        /** 整书装入 */
        WHOLE_BOOK,
        /** 降级为目标章 */
        TARGET_CHAPTER,
        /** S1 选中文字即书内容槽 */
        SELECTION,
        /** 检索式(M4;M3 不可达) */
        RETRIEVAL,
        /** 单章都装不下:优雅错误,不装配 */
        INSUFFICIENT
    }

    /** 候选消息(role + 内容 + 近似 token 数,由调用方经 {@link TokenEstimator} 计)。 */
    public record MessageCandidate(String role, String content, int tokens) {
    }

    /**
     * 计算输入。recentMessages 按<b>旧 → 新</b>自然时间序;
     * wholeBookTokens/targetChapterTokens/selectionTokens 为各自内容的近似 token 数。
     */
    public record BudgetInput(
            Integer contextLimit,
            Integer wholeBookTokens,
            Integer targetChapterTokens,
            Integer selectionTokens,
            List<MessageCandidate> recentMessages,
            boolean retrievalAvailable) {
    }

    /**
     * 装配计划:模式 + 纳入的书内容 token 数 + 保留的最新连续消息段(旧→新原序)
     * + 丢弃条数 + 可读说明(降级/断尾文案,两类可合并;无事件为 null)。
     */
    public record BudgetPlan(
            Mode mode,
            int effectiveLimit,
            int bookContentTokens,
            List<MessageCandidate> keptMessages,
            int droppedCount,
            String note) {
    }

    private BudgetCalculator() {
    }

    public static BudgetPlan calculate(BudgetInput input) {
        int limit = input.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : input.contextLimit();
        List<MessageCandidate> messages =
                input.recentMessages() == null ? List.of() : input.recentMessages();

        // ---- 第一阶段:书内容槽(降级链) ----
        Mode mode;
        int bookTokens;
        String degradeNote = null;
        Integer selection = input.selectionTokens();
        Integer whole = input.wholeBookTokens();
        Integer chapter = input.targetChapterTokens();

        if (selection != null) {
            if (selection > limit) {
                return insufficient(limit, messages.size());
            }
            mode = Mode.SELECTION; // S1:选中文字即槽位,不装整书/整章
            bookTokens = selection;
        } else if (whole != null && whole <= limit) {
            mode = Mode.WHOLE_BOOK;
            bookTokens = whole;
        } else if (chapter != null && chapter <= limit) {
            mode = Mode.TARGET_CHAPTER;
            bookTokens = chapter;
            degradeNote = "整书约 " + whole + " token,超出上下文预算 " + limit
                    + ",已降级为仅装入目标章";
        } else if (input.retrievalAvailable()) {
            mode = Mode.RETRIEVAL; // M4 翻真:检索式装配由调用方按此模式执行
            bookTokens = 0;
            degradeNote = "目标章超出上下文预算,已降级为检索式上下文";
        } else {
            return insufficient(limit, messages.size());
        }

        // ---- 第二阶段:剩余额度装最近对话(新 → 旧,装不下断尾丢最旧,不做摘要) ----
        int remaining = limit - bookTokens;
        List<MessageCandidate> kept = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageCandidate candidate = messages.get(i);
            if (used + candidate.tokens() > remaining) {
                break; // 最旧的不装:保留段永远是时间连续的最新一段
            }
            used += candidate.tokens();
            kept.add(0, candidate);
        }
        int dropped = messages.size() - kept.size();

        String note = degradeNote;
        if (dropped > 0) {
            String truncationNote = "上下文预算有限,已丢弃最旧的 " + dropped + " 条历史消息(不做摘要)";
            note = note == null ? truncationNote : note + ";" + truncationNote;
        }
        return new BudgetPlan(mode, limit, bookTokens, List.copyOf(kept), dropped, note);
    }

    private static BudgetPlan insufficient(int limit, int totalMessages) {
        return new BudgetPlan(Mode.INSUFFICIENT, limit, 0, List.of(), totalMessages,
                "上下文不足:请换大上下文模型,或配置 embedding 后使用检索式提问");
    }
}
