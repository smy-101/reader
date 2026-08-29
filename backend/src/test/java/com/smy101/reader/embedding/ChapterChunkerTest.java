package com.smy101.reader.embedding;

import com.smy101.reader.chat.budget.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块器(§6.5 定稿,M4-01):目标约 500 字/块、段落优先、中文标点感知断点、
 * 超长无断点段强切不丢字、空章零块、多章独立、token_count 与 D-37 口径一致。
 * 纯 JUnit、无 Spring(沿用 BudgetCalculator/TokenEstimator 模式)。
 */
class ChapterChunkerTest {

    // ---- 段落优先断点 ----

    @Test
    void 小段落贪心装块_块在段落边界切分() {
        // 10 个 60 字段落,共 600 字:贪心装到 480(8 段)后第 9 段放不下 → 切块
        String content = String.join("\n\n", repeatParagraphs("段", 60, 10));

        List<TextChunk> chunks = ChapterChunker.chunk(content);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content().split("\n")).hasSize(8); // 前 8 段
        assertThat(chunks.get(1).content().split("\n")).hasSize(2); // 后 2 段
        assertThat(allContent(chunks)).doesNotContain("\n\n"); // 块内段落以单换行连接
    }

    @Test
    void 同输入切块结果确定性() {
        String content = String.join("\n\n", java.util.stream.Stream.of(
                repeatParagraphs("甲段落内容。", 3),
                repeatParagraphs("乙段落内容,还有更多。", 40),
                repeatParagraphs("丙。", 2)).flatMap(List::stream).toList());

        List<TextChunk> first = ChapterChunker.chunk(content);
        List<TextChunk> second = ChapterChunker.chunk(content);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 目标块长附近的断点选择_恰好五百字一段一块() {
        String exact = "好".repeat(500);
        List<TextChunk> chunks = ChapterChunker.chunk(exact);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).hasSize(500);
    }

    // ---- 中文标点感知 ----

    @Test
    void 超长段落在中文句读标点处断开() {
        // 每句 15 字(14 字 + 句号),共 40 句 = 600 字 → 两块,句边界对齐
        String sentence = "这是一句完整的话总共十九个字。";
        assertThat(sentence.length()).isEqualTo(15);
        String paragraph = sentence.repeat(40);

        List<TextChunk> chunks = ChapterChunker.chunk(paragraph);

        assertThat(chunks).hasSize(2);
        for (TextChunk chunk : chunks) {
            assertThat(chunk.content()).endsWith("。"); // 断在句号后,不腰斩句子
            assertThat(chunk.content().split("。")).allSatisfy(
                    s -> assertThat(s.length()).isLessThanOrEqualTo(14 + 1)); // 每句完整
        }
        assertThat(allContent(chunks)).isEqualTo(paragraph); // 不丢不重
    }

    @Test
    void 句末后随引号跟随断点() {
        // 「……」引号包裹的对白:断点在 」 后,不在 。 后
        String sentence = "「这是一句对白共十几个字。」";
        String paragraph = sentence.repeat(50); // 40 字/句 × 50 = 2000 字

        List<TextChunk> chunks = ChapterChunker.chunk(paragraph);

        for (TextChunk chunk : chunks) {
            assertThat(chunk.content()).endsWith("」");
        }
        assertThat(allContent(chunks)).isEqualTo(paragraph);
    }

    // ---- 超长无断点段强切 ----

    @Test
    void 无断点超长段强切_不丢失不重复() {
        String paragraph = "字".repeat(1200);

        List<TextChunk> chunks = ChapterChunker.chunk(paragraph);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).content()).hasSize(500);
        assertThat(chunks.get(1).content()).hasSize(500);
        assertThat(chunks.get(2).content()).hasSize(200);
        assertThat(allContent(chunks)).isEqualTo(paragraph); // 拼回原文
    }

    @Test
    void 强切不腰斩代理对字符() {
        // 扩展 B 区字符(代理对):500 恰落在中间时切点让一步,不产生半个字符
        // 前缀 1 个 BMP 字 → 代理对起始于奇数下标,切点 500(偶数)恰好落在一对中间
        String paragraph = "字" + "𠀀".repeat(1199); // 1 + 2398 = 2399 个 UTF-16 单元

        List<TextChunk> chunks = ChapterChunker.chunk(paragraph);

        for (TextChunk chunk : chunks) {
            String text = chunk.content();
            assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
            assertThat(text.codePointCount(0, text.length())).isLessThanOrEqualTo(ChapterChunker.TARGET_CHARS + 1);
        }
        assertThat(chunks.stream().mapToInt(c -> c.content().length()).sum()).isEqualTo(paragraph.length());
    }

    // ---- 空章与纯空白章 ----

    @Test
    void 空章与纯空白章产出零块() {
        assertThat(ChapterChunker.chunk(null)).isEmpty();
        assertThat(ChapterChunker.chunk("")).isEmpty();
        assertThat(ChapterChunker.chunk("  \n\n\t \n  \u3000")).isEmpty();
    }

    // ---- 多章独立 ----

    @Test
    void 多章独立切块_各自成序() {
        // 切块器按章调用,每章结果独立;章内块序由调用方推进(嵌入任务域)
        List<TextChunk> chapterOne = ChapterChunker.chunk(String.join("\n\n", repeatParagraphs("第一章内容。", 60)));
        List<TextChunk> chapterTwo = ChapterChunker.chunk(String.join("\n\n", repeatParagraphs("第二章完全不同的内容!", 40)));

        assertThat(chapterOne).hasSize(1);
        assertThat(chapterTwo).hasSize(1);
        assertThat(chapterOne.get(0).content()).contains("第一章");
        assertThat(chapterTwo.get(0).content()).contains("第二章");
    }

    // ---- token_count 口径 ----

    @Test
    void 块tokenCount与TokenEstimator一致() {
        String content = String.join("\n\n", java.util.stream.Stream.of(
                repeatParagraphs("中文段落若干字。", 30),
                repeatParagraphs("English words mixed in. ", 20)).flatMap(List::stream).toList());

        List<TextChunk> chunks = ChapterChunker.chunk(content);

        assertThat(chunks).isNotEmpty();
        for (TextChunk chunk : chunks) {
            assertThat(chunk.tokenCount()).isEqualTo(TokenEstimator.estimate(chunk.content()));
        }
        // 块不超过目标字数(约 500:强切让步代理对时至多 +1)
        for (TextChunk chunk : chunks) {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(ChapterChunker.TARGET_CHARS + 1);
        }
    }

    // ---- helpers ----

    private static List<String> repeatParagraphs(String seed, int charTarget) {
        return repeatParagraphs(seed, charTarget, 1);
    }

    /** 生成指定段数、每段约 charTarget 字的段落列表(以种子重复凑齐)。 */
    private static List<String> repeatParagraphs(String seed, int charTarget, int paragraphs) {
        StringBuilder one = new StringBuilder();
        while (one.length() < charTarget) {
            one.append(seed);
        }
        return java.util.stream.IntStream.range(0, paragraphs)
                .mapToObj(i -> one.toString())
                .collect(Collectors.toList());
    }

    private static String allContent(List<TextChunk> chunks) {
        return chunks.stream().map(TextChunk::content).collect(Collectors.joining());
    }
}
