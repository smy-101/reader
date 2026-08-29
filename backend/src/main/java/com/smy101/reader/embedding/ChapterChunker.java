package com.smy101.reader.embedding;

import com.smy101.reader.chat.budget.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节正文切块器(§6.5 定稿,M4-01):纯函数、无 Spring(沿用 BudgetCalculator 模式)。
 * <p>
 * 输入 = 章节清洗后纯文本(D-26/D-40,块间以空行分隔);输出 = 向量块序列:
 * <ol>
 *   <li>段落优先:按空行拆段,段落贪心装块,目标约 {@link #TARGET_CHARS} 字/块(块不超目标);</li>
 *   <li>中文标点感知:段超长时在句读标点(。!?…;、及对应 ASCII 形式)处断开,
 *       断点后随的引号/括号等收尾符号与句同块;</li>
 *   <li>超长无断点段强切:按目标字数硬切,不丢字、不重字;切点落在代理对中间时让一步;</li>
 *   <li>空章/纯空白章产出零块;每次调用处理一章,章内块序由调用方(嵌入任务域)推进;</li>
 *   <li>块 token_count 用 {@link TokenEstimator}(D-37),与上下文预算共用同一计数源。</li>
 * </ol>
 * 同输入切块结果确定性可复现。
 */
public final class ChapterChunker {

    /** 目标块长(约 500 字/块,§6.5)。 */
    public static final int TARGET_CHARS = 500;

    /** 句读断点:中文句末/停顿标点 + 对应 ASCII 形式(混排正文友好)。 */
    private static final String BREAKERS = "。!?…!?;;,、:,.:;";

    /** 断点后随的收尾符号(引号/括号):吸收进前句,断点不落在引号前。 */
    private static final String CLOSERS = "\u300D\u300F\u300B\u201D\u2019\u3015\u3011\uFF09)";

    private ChapterChunker() {
    }

    /** 默认目标块长切块。 */
    public static List<TextChunk> chunk(String content) {
        return chunk(content, TARGET_CHARS);
    }

    /** 切块;content 为 null/空/纯空白返回空列表(空章跳过)。 */
    public static List<TextChunk> chunk(String content, int targetChars) {
        if (targetChars <= 0) {
            throw new IllegalArgumentException("目标块长必须为正");
        }
        List<String> packed = pack(units(paragraphs(content), targetChars), targetChars);
        List<TextChunk> chunks = new ArrayList<>(packed.size());
        for (String text : packed) {
            chunks.add(new TextChunk(text, TokenEstimator.estimate(text)));
        }
        return chunks;
    }

    // ---- 第一步:拆段(空行分隔;strip 后空白段丢弃) ----

    private static List<String> paragraphs(String content) {
        List<String> result = new ArrayList<>();
        if (content == null) {
            return result;
        }
        for (String paragraph : content.split("\\n{2,}")) {
            String stripped = paragraph.strip();
            if (!stripped.isEmpty()) {
                result.add(stripped);
            }
        }
        return result;
    }

    // ---- 第二步:拆句装单元(段落 ≤ 目标 = 单元;超长段按句读断点拆,无断点句强切) ----

    /** 装配单元:text + 是否开启新段落(决定块内连接符:新段换行、同段直连)。 */
    private record Unit(String text, boolean startsParagraph) {
    }

    private static List<Unit> units(List<String> paragraphs, int targetChars) {
        List<Unit> units = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (paragraph.length() <= targetChars) {
                units.add(new Unit(paragraph, true));
                continue;
            }
            boolean first = true;
            for (String sentence : sentences(paragraph)) {
                if (sentence.length() <= targetChars) {
                    units.add(new Unit(sentence, first));
                } else {
                    for (String piece : hardCut(sentence, targetChars)) {
                        units.add(new Unit(piece, first));
                    }
                }
                first = false;
            }
        }
        return units;
    }

    /** 句读断点拆句:断点 = 标点后(吸收连续标点与收尾符号);无断点返回整段单句。 */
    private static List<String> sentences(String paragraph) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < paragraph.length()) {
            if (BREAKERS.indexOf(paragraph.charAt(i)) >= 0) {
                int end = i + 1;
                while (end < paragraph.length()
                        && (BREAKERS.indexOf(paragraph.charAt(end)) >= 0
                        || CLOSERS.indexOf(paragraph.charAt(end)) >= 0)) {
                    end++;
                }
                sentences.add(paragraph.substring(start, end));
                start = end;
                i = end;
            } else {
                i++;
            }
        }
        if (start < paragraph.length()) {
            sentences.add(paragraph.substring(start));
        }
        return sentences;
    }

    /** 超长无断点句强切:按目标字数硬切;切点落在代理对中间让一步(不腰斩字符)。 */
    private static List<String> hardCut(String sentence, int targetChars) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + targetChars, sentence.length());
            if (end < sentence.length() && Character.isHighSurrogate(sentence.charAt(end - 1))) {
                end++;
            }
            pieces.add(sentence.substring(start, end));
            start = end;
        }
        return pieces;
    }

    // ---- 第三步:贪心装块(块不超目标;新段以换行连接,同段句直连) ----

    private static List<String> pack(List<Unit> units, int targetChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Unit unit : units) {
            String separator = current.isEmpty() ? "" : (unit.startsParagraph() ? "\n" : "");
            if (!current.isEmpty()
                    && current.length() + separator.length() + unit.text().length() > targetChars) {
                chunks.add(current.toString());
                current.setLength(0);
                separator = "";
            }
            current.append(separator).append(unit.text());
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
