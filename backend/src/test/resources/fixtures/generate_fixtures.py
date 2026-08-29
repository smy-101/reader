#!/usr/bin/env python3
"""生成后端集成测试用的 EPUB fixtures(README 见本目录)。

用法:python3 generate_fixtures.py   (在 fixtures 目录内执行)

产出五个文件:
- normal.epub  正常书:3 章,含图片(应被丢)、表格(应拍平)、脚注(应并入章末)、
               代码块(应保留)、EPUB3 nav 目录、封面(PNG)
- corrupt.epub 损坏文件:合法 zip 头 + 截断的内容
- drm.epub     DRM 构造书:合法 EPUB + META-INF/encryption.xml 加密标记
- chibi.epub   第二本书(S4 跨书检索):赤壁赋选,与 normal.epub 内容域互斥,
               两书同传后袋向量检索可确定性断言“命中跨两书”
"""

import io
import os
import struct
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

# 1x1 红色像素 PNG
PNG_1PX = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108020000009077"
    "3df80000000c4944415408d763f8cfc00000030101"
    "00c9fe92ef0000000049454e44ae426082"
)

CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:fixture-normal-0001</dc:identifier>
    <dc:title>fixture 正常书</dc:title>
    <dc:creator>张三</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
    <meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
    <item id="nav" href="../nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
    <item id="c4" href="ch4.xhtml" media-type="application/xhtml+xml"/>
    <item id="c5" href="ch5.xhtml" media-type="application/xhtml+xml"/>
    <item id="style" href="style.css" media-type="text/css"/>
  </manifest>
  <spine>
    <itemref idref="nav"/>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="c3"/>
    <itemref idref="c4" linear="no"/>
    <itemref idref="c5"/>
  </spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head>
<body>
  <nav epub:type="toc">
    <ol>
      <li><a href="OEBPS/ch1.xhtml">第一章 起点</a></li>
      <li><a href="OEBPS/ch2.xhtml">第二章 表格与图片</a></li>
      <li><a href="OEBPS/ch3.xhtml">第三章 代码与脚注</a></li>
    </ol>
  </nav>
</body>
</html>
"""

CH1 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>ch1</title><link rel="stylesheet" href="style.css"/></head>
<body>
  <h1>第一章 起点</h1>
  <p>这是第一章的正文第一段,包含英文 mixed content and spaces。</p>
  <p>这是第二段。段落之间应当以空行分隔。</p>
</body>
</html>
"""

CH2 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>ch2</title></head>
<body>
  <h1>第二章 表格与图片</h1>
  <p>本章包含一张图片,清洗后应被丢弃,本段文字保留。</p>
  <p><img src="cover.png" alt="插图"/>图片所在段落若仅剩引用文字则保留引用文字。</p>
  <table>
    <tr><th>语言</th><th>年份</th></tr>
    <tr><td>Java</td><td>1995</td></tr>
    <tr><td>Go</td><td>2009</td></tr>
  </table>
  <p>表格之后仍有一段正文。</p>
</body>
</html>
"""

CH3 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>ch3</title></head>
<body>
  <h1>第三章 代码与脚注</h1>
  <p>本段末尾带一个脚注引用<a epub:type="noteref" href="#fn1">[1]</a>。</p>
  <pre><code>public class Main {
    public static void main(String[] args) {
        System.out.println("hello");
    }
}</code></pre>
  <aside epub:type="footnote" id="fn1"><p>这是脚注一的正文内容。</p></aside>
  <aside epub:type="footnote" id="fn2"><p>外层脚注二<aside epub:type="footnote">嵌套内层脚注不应重复入章末</aside></p></aside>
  <p>脚注元素本体不应出现在原位置,应并入章末。</p>
</body>
</html>
"""

CH4 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>ch4</title></head>
<body>
  <h1>附录答案页</h1>
  <p>本页在 spine 中标记 linear="no"，不属于阅读顺序，不应入库。</p>
</body>
</html>
"""

CH5 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>ch5</title></head>
<body>
  <p>这一章没有任何标题元素，也不在目录里，章节标题应为 NULL。</p>
</body>
</html>
"""

CHIBI_OPF = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:fixture-chibi-0002</dc:identifier>
    <dc:title>赤壁赋选</dc:title>
    <dc:creator>苏轼</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
    <meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>
    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>
"""

CHIBI_NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head>
<body>
  <nav epub:type="toc">
    <ol>
      <li><a href="ch1.xhtml">壬戌之秋</a></li>
      <li><a href="ch2.xhtml">哀吾生之须臾</a></li>
    </ol>
  </nav>
</body>
</html>
"""

CHIBI_CH1 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>ch1</title></head>
<body>
  <h1>壬戌之秋</h1>
  <p>壬戌之秋,七月既望,苏子与客泛舟游于赤壁之下。</p>
  <p>清风徐来,水波不兴。举酒属客,诵明月之诗,歌窈窕之章。</p>
  <p>少焉,月出于东山之上,徘徊于斗牛之间。白露横江,水光接天。</p>
</body>
</html>
"""

CHIBI_CH2 = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>ch2</title></head>
<body>
  <h1>哀吾生之须臾</h1>
  <p>寄蜉蝣于天地,渺沧海之一粟。哀吾生之须臾,羡长江之无穷。</p>
  <p>挟飞仙以遨游,抱明月而长终。知不可乎骤得,托遗响于悲风。</p>
</body>
</html>
"""

DRM = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
    <EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/>
    <CipherData><CipherReference URI="OEBPS/ch1.xhtml"/></CipherData>
  </EncryptedData>
</encryption>
"""

FONT_OBFUSCATION = """<?xml version="1.0" encoding="UTF-8"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
    <EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
    <CipherData><CipherReference URI="OEBPS/fonts/body.otf"/></CipherData>
  </EncryptedData>
</encryption>
"""


def write_epub(path: str, entries: list[tuple[str, bytes]]) -> None:
    with zipfile.ZipFile(path, "w") as zf:
        # EPUB 规范:mimetype 须为首条且 STORED
        for name, data in entries:
            if name == "mimetype":
                zi = zipfile.ZipInfo(name)
                zi.compress_type = zipfile.ZIP_STORED
                zf.writestr(zi, data)
            else:
                zf.writestr(name, data)


def build_normal() -> None:
    entries = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER_XML.encode()),
        ("nav.xhtml", NAV.encode()),  # nav 与 OPF 不同目录:验证 href 相对 nav 文档解析
        ("OEBPS/content.opf", OPF.encode()),
        ("OEBPS/ch1.xhtml", CH1.encode()),
        ("OEBPS/ch2.xhtml", CH2.encode()),
        ("OEBPS/ch3.xhtml", CH3.encode()),
        ("OEBPS/ch4.xhtml", CH4.encode()),
        ("OEBPS/ch5.xhtml", CH5.encode()),
        ("OEBPS/cover.png", PNG_1PX),
        ("OEBPS/style.css", b"body{margin:0}"),
    ]
    write_epub(os.path.join(HERE, "normal.epub"), entries)


def build_chibi() -> None:
    """S4 跨书检索第二本书:赤壁赋选(内容域与 normal.epub 互斥,书名/正文均不同)。"""
    entries = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER_XML.encode()),
        ("OEBPS/content.opf", CHIBI_OPF.encode()),
        ("OEBPS/nav.xhtml", CHIBI_NAV.encode()),
        ("OEBPS/ch1.xhtml", CHIBI_CH1.encode()),
        ("OEBPS/ch2.xhtml", CHIBI_CH2.encode()),
        ("OEBPS/cover.png", PNG_1PX),
    ]
    write_epub(os.path.join(HERE, "chibi.epub"), entries)


def build_corrupt() -> None:
    """合法 zip 头 + 截断数据:解析必然失败,且失败原因应是 zip 层而非 XML 层。"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("mimetype", b"application/epub+zip")
        zf.writestr("META-INF/container.xml", CONTAINER_XML)
    blob = bytearray(buf.getvalue())
    # 截掉尾部一半:central directory 损坏 → 读取 zip 目录时抛异常
    truncated = bytes(blob[: len(blob) // 2])
    with open(os.path.join(HERE, "corrupt.epub"), "wb") as f:
        f.write(truncated)


def build_drm() -> None:
    entries = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER_XML.encode()),
        ("META-INF/encryption.xml", DRM.encode()),
        ("OEBPS/content.opf", OPF.encode()),
        ("OEBPS/nav.xhtml", NAV.encode()),
        ("OEBPS/ch1.xhtml", CH1.encode()),
        ("OEBPS/cover.png", PNG_1PX),
    ]
    write_epub(os.path.join(HERE, "drm.epub"), entries)


def build_font_obfuscated() -> None:
    """仅字体混淆(合法)的 EPUB:不应被 DRM 判定拦截,可正常导入。"""
    entries = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER_XML.encode()),
        ("META-INF/encryption.xml", FONT_OBFUSCATION.encode()),
        ("OEBPS/content.opf", OPF.encode()),
        ("OEBPS/ch1.xhtml", CH1.encode()),
        ("OEBPS/ch2.xhtml", CH2.encode()),
        ("OEBPS/ch3.xhtml", CH3.encode()),
        ("OEBPS/cover.png", PNG_1PX),
    ]
    write_epub(os.path.join(HERE, "font-obfuscated.epub"), entries)


if __name__ == "__main__":
    build_normal()
    build_chibi()
    build_corrupt()
    build_drm()
    build_font_obfuscated()
    for name in ("normal.epub", "chibi.epub", "corrupt.epub", "drm.epub", "font-obfuscated.epub"):
        p = os.path.join(HERE, name)
        print(name, os.path.getsize(p), "bytes")
