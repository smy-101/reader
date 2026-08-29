package com.smy101.reader.chat.budget;

/**
 * D-37 token 字符近似计数(ADR-0007):中文(含 CJK 标点/全角/假名)≈ 1 token/字,
 * 其余字符 ≈ 1 token/4 字符,两组分别累计后求和、向上取整——宁可高估提前降级,
 * 不引 tokenizer 依赖。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /** 近似 token 数;null/空串返回 0。 */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjkLike(cp)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + ceilDiv(other, 4);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * 中文口径的"一个字":CJK 统一表意与扩展、兼容表意、CJK 标点、全角形式、假名。
     * 扩展 B+(补充平面)按区间粗粒度覆盖,近似口径够用。
     */
    private static boolean isCjkLike(int cp) {
        return (cp >= 0x3000 && cp <= 0x30FF) // CJK 标点 + 假名
                || (cp >= 0x3400 && cp <= 0x4DBF) // 扩展 A
                || (cp >= 0x4E00 && cp <= 0x9FFF) // 统一表意(主力)
                || (cp >= 0xF900 && cp <= 0xFAFF) // 兼容表意
                || (cp >= 0xFF00 && cp <= 0xFFEF) // 全角形式(,!?１２３)
                || (cp >= 0x20000 && cp <= 0x2FA1F); // 扩展 B 及以后
    }
}
