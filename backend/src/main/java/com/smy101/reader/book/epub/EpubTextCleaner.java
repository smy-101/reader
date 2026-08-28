package com.smy101.reader.book.epub;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节正文清洗(D-40,细则定稿见 ADR-0005):
 * <ol>
 *   <li>丢图:img/svg/picture/audio/video 等媒体元素整体移除(alt 文字随图丢弃);</li>
 *   <li>脚注并入章末:带 {@code epub:type~=footnote} 或 {@code role=doc-footnote} 的元素从原位置摘出,
 *       正文以「脚注:」前缀追加在章节末尾;</li>
 *   <li>表格拍平为文本:每行单元格文字以单空格连接,行与行换行分隔(嵌套表按最外层拍平);</li>
 *   <li>代码块原样保留:pre 块保留原始换行与缩进;</li>
 *   <li>其余块级元素(p/h1-h6/li/blockquote/div 等)取归一化空白后的文字,块间以空行分隔;
 *       只取最外层块,避免嵌套重复;</li>
 *   <li>清洗后无正文的 spine 项(如纯封面页、nav)不构成章节。</li>
 * </ol>
 */
final class EpubTextCleaner {

    private static final String[] REMOVED_MEDIA = {
            "img", "svg", "picture", "audio", "video", "source", "track", "map", "object", "embed"
    };

    private static final String[] BLOCK_TAGS = {
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre",
            "figcaption", "div", "dt", "dd", "hr"
    };

    private EpubTextCleaner() {
    }

    /** 清洗后的章节纯文本;无正文返回空字符串。 */
    static String clean(Document doc) {
        for (String tag : REMOVED_MEDIA) {
            doc.select(tag).remove();
        }

        List<String> footnoteTexts = new ArrayList<>();
        // 嵌套脚注(如 aside 内带脚注属性的子元素)只取最外层,避免同一文本重复入章末;
        // 判定必须在任何移除前完成(移除后 ancestors 链断);追加顺序保持文档顺序
        List<Element> footnotes = doc.body().select("*").stream()
                .filter(EpubTextCleaner::isFootnote)
                .toList();
        java.util.Set<Element> outermost = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        footnotes.stream()
                .filter(el -> el.parents().stream().noneMatch(footnotes::contains))
                .forEach(outermost::add);
        for (Element el : footnotes) {
            if (!outermost.contains(el)) {
                continue;
            }
            String text = el.text().strip();
            if (!text.isEmpty()) {
                footnoteTexts.add(text);
            }
            el.remove();
        }

        flattenTables(doc);

        List<String> blocks = new ArrayList<>();
        for (Element el : doc.body().select(String.join(",", BLOCK_TAGS))) {
            if (isOutermostBlock(el)) {
                String text = "pre".equals(el.tagName())
                        ? el.wholeText().strip()          // 代码块原样保留
                        : el.text().strip();              // 归一化空白
                if (!text.isEmpty()) {
                    blocks.add(text);
                }
            }
        }

        for (String footnote : footnoteTexts) {
            blocks.add("脚注:" + footnote);
        }

        return String.join("\n\n", blocks);
    }

    private static boolean isFootnote(Element el) {
        String epubType = el.attr("epub:type").toLowerCase();
        if (epubType.contains("footnote") || epubType.contains("rearnote")) {
            return true;
        }
        return "doc-footnote".equals(el.attr("role"));
    }

    /** 表格拍平:最外层 table 整体替换为文本块(行=单元格以空格连接,行间换行)。 */
    private static void flattenTables(Document doc) {
        for (Element table : doc.select("table")) {
            // 只处理最外层表,避免嵌套表行重复
            if (table.parents().is("table")) {
                continue;
            }
            List<String> rows = new ArrayList<>();
            for (Element tr : table.select("tr")) {
                String row = tr.select("td,th").stream()
                        .map(Element::text)
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .reduce((a, b) -> a + " " + b)
                        .orElse("");
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
            table.replaceWith(doc.createElement("pre").text(String.join("\n", rows)));
        }
    }

    /** 嵌套块只取最外层(如 div>p 只由 p 出字)。 */
    private static boolean isOutermostBlock(Element el) {
        return el.parents().stream().noneMatch(p -> isBlockTag(p.tagName()));
    }

    private static boolean isBlockTag(String tagName) {
        for (String tag : BLOCK_TAGS) {
            if (tag.equals(tagName)) {
                return true;
            }
        }
        return false;
    }
}
