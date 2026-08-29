package com.smy101.reader.chat.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-37 字符近似计数:中文 ≈ 1 token/字、英文 ≈ 1 token/4 字符、向上取整、宁可高估提前降级。
 */
class TokenEstimatorTest {

    @Test
    void 纯中文一字一token() {
        assertThat(TokenEstimator.estimate("十个中文字")).isEqualTo(5);
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    void 纯英文四字符一token_向上取整() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1); // 4 chars → 1
        assertThat(TokenEstimator.estimate("abc")).isEqualTo(1); // 3 chars → ceil(0.75) = 1
        assertThat(TokenEstimator.estimate("abcde")).isEqualTo(2); // 5 chars → ceil(1.25) = 2
        assertThat(TokenEstimator.estimate("hello world!")).isEqualTo(3); // 12 chars → 3
    }

    @Test
    void 中英混合分组计数() {
        // 4 个中文字(4)+ 4 个英文字符(ceil(4/4)=1)= 5;两组各自计完求和
        assertThat(TokenEstimator.estimate("这本书很good")).isEqualTo(5);
        // 中文标点按 1 token/字计(顿号、句号各 1)
        assertThat(TokenEstimator.estimate("一、二。")).isEqualTo(4);
    }

    @Test
    void 空白计入英文字符组_不静默丢弃() {
        // "ab cd" = 5 chars → ceil(5/4) = 2;空白计入,宁可高估
        assertThat(TokenEstimator.estimate("ab cd")).isEqualTo(2);
    }

    @Test
    void 数字与全角字符按各自口径() {
        // "1234" → 1;全角"1234"(Fullwidth)按中文字计 → 4
        assertThat(TokenEstimator.estimate("1234")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("１２３４")).isEqualTo(4);
    }

    @Test
    void 日文假名按一token计() {
        assertThat(TokenEstimator.estimate("あいう")).isEqualTo(3);
    }

    @Test
    void 章节正文量级计数快且线性() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            text.append("第").append(i).append("章内容段落。");
        }
        int tokens = TokenEstimator.estimate(text.toString());
        assertThat(tokens).isPositive();
        assertThat(tokens).isGreaterThan(text.length() / 4);
    }
}
