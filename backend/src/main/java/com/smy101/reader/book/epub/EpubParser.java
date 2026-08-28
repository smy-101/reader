package com.smy101.reader.book.epub;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB 同步解析器(FR-101,D-41:无异步任务)。
 * <p>
 * zip → container.xml → OPF(manifest/spine/metadata)→ 章节正文清洗(D-40)与封面。
 * 解析失败抛 {@link EpubParseException}(文件损坏)或 {@link EpubDrmException}(疑似 DRM),
 * 由全局异常处理转 400 可读文案;失败时调用方不得入库、不得落盘(D-29:先解析后落盘)。
 */
@Component
public class EpubParser {

    private static final String CONTAINER_PATH = "META-INF/container.xml";
    private static final String ENCRYPTION_PATH = "META-INF/encryption.xml";
    private static final Map<String, String> COVER_EXT_BY_MEDIA_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp",
            "image/svg+xml", "svg");

    public ParsedEpub parse(byte[] bytes) {
        Map<String, byte[]> entries = readZip(bytes);

        // DRM 识别在解析之前(D-29):有加密标记即拒,不解密
        if (entries.containsKey(ENCRYPTION_PATH)) {
            throw new EpubDrmException("疑似 DRM 保护,不支持导入");
        }

        // container.xml → OPF 路径
        Document container = parseXml(required(entries, CONTAINER_PATH), CONTAINER_PATH);
        Element rootFile = container.selectFirst("rootfile");
        if (rootFile == null || !rootFile.hasAttr("full-path")) {
            throw new EpubParseException("文件损坏:container.xml 缺少 rootfile");
        }
        String opfPath = rootFile.attr("full-path");
        Document opfDoc = parseXml(required(entries, opfPath), opfPath);

        return buildResult(entries, opfPath, opfDoc);
    }

    private ParsedEpub buildResult(Map<String, byte[]> entries, String opfPath, Document opf) {
        String title = firstMetaText(opf, "title");
        String author = firstMetaText(opf, "creator");
        String language = firstMetaText(opf, "language");
        if (title == null || title.isBlank()) {
            throw new EpubParseException("文件损坏:EPUB 缺少标题元数据");
        }

        // manifest:id → item
        Map<String, Element> manifest = new LinkedHashMap<>();
        Element manifestEl = opf.selectFirst("manifest");
        if (manifestEl != null) {
            for (Element item : manifestEl.select("item")) {
                manifest.put(item.attr("id"), item);
            }
        }

        Map<String, String> navTitles = readNavTitles(entries, opfPath, manifest);

        // spine 顺序 → 章节(仅有正文的内容文件,D-40)
        List<ParsedEpub.ParsedChapter> chapters = new ArrayList<>();
        Element spine = opf.selectFirst("spine");
        if (spine != null) {
            for (Element itemref : spine.select("itemref")) {
                Element item = manifest.get(itemref.attr("idref"));
                if (item == null || !isContentDoc(item)) {
                    continue;
                }
                // EPUB3 nav 文档 = 目录本体,仅导航视图不入库(D-40)
                if (item.attr("properties").contains("nav")) {
                    continue;
                }
                String href = resolveHref(opfPath, item.attr("href"));
                byte[] doc = entries.get(href);
                if (doc == null) {
                    continue;
                }
                String content = EpubTextCleaner.clean(Jsoup.parse(new String(doc, java.nio.charset.StandardCharsets.UTF_8), href));
                if (content.isBlank()) {
                    continue; // 清洗后无正文(纯封面页/nav)不入库
                }
                chapters.add(new ParsedEpub.ParsedChapter(
                        chapters.size() + 1,
                        chapterTitle(navTitles, href, content),
                        href,
                        content));
            }
        }
        if (chapters.isEmpty()) {
            throw new EpubParseException("文件损坏:EPUB 中未找到有正文的章节");
        }

        return new ParsedEpub(title, author, language, chapters, readCover(entries, opfPath, opf, manifest));
    }

    // ---- 元数据 ----

    /** 取 metadata 下 dc:title / dc:creator / dc:language 的首个文本(命名空间前缀无关)。 */
    private String firstMetaText(Document opf, String dcElement) {
        Element metadata = opf.selectFirst("metadata");
        if (metadata == null) {
            return null;
        }
        for (Element el : metadata.children()) {
            if (el.tagName().toLowerCase(Locale.ROOT).endsWith(":" + dcElement)
                    || el.tagName().equalsIgnoreCase(dcElement)) {
                String text = el.text().strip();
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    private boolean isContentDoc(Element item) {
        String type = item.attr("media-type").toLowerCase(Locale.ROOT);
        return type.equals("application/xhtml+xml") || type.equals("text/html");
    }

    // ---- 章节标题:nav 目录优先,退回首标题 ----

    private Map<String, String> readNavTitles(Map<String, byte[]> entries, String opfPath, Map<String, Element> manifest) {
        for (Element item : manifest.values()) {
            if (!item.attr("properties").contains("nav")) {
                continue;
            }
            byte[] nav = entries.get(resolveHref(opfPath, item.attr("href")));
            if (nav == null) {
                continue;
            }
            Map<String, String> titles = new HashMap<>();
            Document navDoc = Jsoup.parse(new String(nav, java.nio.charset.StandardCharsets.UTF_8), "");
            for (Element a : navDoc.select("nav a[href]")) {
                String href = resolveHref(opfPath, a.attr("href").split("#")[0]);
                titles.putIfAbsent(href, a.text().strip());
            }
            return titles;
        }
        return Map.of();
    }

    private String chapterTitle(Map<String, String> navTitles, String href, String content) {
        String navTitle = navTitles.get(href);
        if (navTitle != null && !navTitle.isBlank()) {
            return navTitle;
        }
        // 退路:正文首个标题行
        for (String line : content.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return null;
    }

    // ---- 封面 ----

    private ParsedEpub.ParsedCover readCover(Map<String, byte[]> entries, String opfPath,
                                             Document opf, Map<String, Element> manifest) {
        String coverId = null;
        for (Element item : manifest.values()) {
            if (item.attr("properties").contains("cover-image")) {
                coverId = item.attr("id");
                break;
            }
        }
        if (coverId == null) {
            // EPUB2 习惯:<meta name="cover" content="idref"/>
            for (Element meta : opf.select("meta[name=cover]")) {
                coverId = meta.attr("content");
                break;
            }
        }
        if (coverId == null) {
            return null;
        }
        Element item = manifest.get(coverId);
        if (item == null) {
            return null;
        }
        String href = resolveHref(opfPath, item.attr("href"));
        byte[] bytes = entries.get(href);
        if (bytes == null) {
            return null;
        }
        String ext = COVER_EXT_BY_MEDIA_TYPE.get(item.attr("media-type").toLowerCase(Locale.ROOT));
        if (ext == null) {
            ext = "img";
        }
        return new ParsedEpub.ParsedCover(bytes, ext);
    }

    // ---- zip / xml 基建 ----

    private Map<String, byte[]> readZip(byte[] bytes) {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new EpubParseException("文件损坏:无法按 zip 结构读取该 EPUB", e);
        }
        if (entries.isEmpty()) {
            throw new EpubParseException("文件损坏:文件不是有效的 EPUB(zip 内无内容)");
        }
        return entries;
    }

    private byte[] required(Map<String, byte[]> entries, String path) {
        byte[] data = entries.get(path);
        if (data == null) {
            throw new EpubParseException("文件损坏:缺少 " + path);
        }
        return data;
    }

    private Document parseXml(byte[] xml, String path) {
        try {
            return Jsoup.parse(new String(xml, java.nio.charset.StandardCharsets.UTF_8), "", Parser.xmlParser());
        } catch (RuntimeException e) {
            throw new EpubParseException("文件损坏:" + path + " 无法解析", e);
        }
    }

    /** 相对 OPF 目录解析 href 为 zip 内绝对路径,归一化 ../ 段。 */
    private String resolveHref(String opfPath, String href) {
        String opfDir = "";
        int slash = opfPath.lastIndexOf('/');
        if (slash >= 0) {
            opfDir = opfPath.substring(0, slash + 1);
        }
        String combined = opfDir + href;
        String[] parts = combined.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        return String.join("/", stack);
    }
}
