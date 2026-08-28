#!/usr/bin/env python3
"""生成 M1 E2E 用的多章带目录 EPUB fixture(apps/web/e2e/fixtures,在 fixtures 目录内执行)。

- m1-e2e.epub:五章正文 + EPUB3 nav(嵌套两级目录)+ 封面 PNG,供 03/05/07/08 复用:
  目录跳转(嵌套)、多章翻页/进度百分比、跨章划线都有材料。
复用口径与 backend/src/test/resources/fixtures/generate_fixtures.py 一致(纯 zipfile 构造)。
"""

import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

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
    <dc:identifier id="uid">urn:uuid:fixture-m1-e2e-0001</dc:identifier>
    <dc:title>赤壁赋与前后出师表</dc:title>
    <dc:creator>苏轼·诸葛亮</dc:creator>
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
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
    <itemref idref="c3"/>
    <itemref idref="c4"/>
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
      <li><a href="OEBPS/ch1.xhtml">卷一 · 出师表</a>
        <ol>
          <li><a href="OEBPS/ch1.xhtml">先帝创业未半</a></li>
          <li><a href="OEBPS/ch2.xhtml">宫中府中</a></li>
        </ol>
      </li>
      <li><a href="OEBPS/ch3.xhtml">卷二 · 后出师表</a></li>
      <li><a href="OEBPS/ch4.xhtml">卷三 · 赤壁赋</a>
        <ol>
          <li><a href="OEBPS/ch4.xhtml">壬戌之秋</a></li>
          <li><a href="OEBPS/ch5.xhtml">哀吾生之须臾</a></li>
        </ol>
      </li>
    </ol>
  </nav>
</body>
</html>
"""


def chapter(title: str, paras: list[str]) -> str:
    body = "\n".join(f"    <p>{p}</p>" for p in paras)
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>{title}</title></head>
<body>
  <h1>{title}</h1>
{body}
</body>
</html>
"""


CH1 = chapter("先帝创业未半", [
    "先帝创业未半而中道崩殂,今天下三分,益州疲弊,此诚危急存亡之秋也。",
    "然侍卫之臣不懈于内,忠志之士忘身于外者,盖追先帝之殊遇,欲报之于陛下也。",
    "诚宜开张圣听,以光先帝遗德,恢弘志士之气,不宜妄自菲薄,引喻失义,以塞忠谏之路也。",
    "第一段正文到此为止,这一章用于翻页与进度观察。",
])
CH2 = chapter("宫中府中", [
    "宫中府中,俱为一体,陟罚臧否,不宜异同。",
    "若有作奸犯科及为忠善者,宜付有司论其刑赏,以昭陛下平明之理,不宜偏私,使内外异法也。",
    "侍中、侍郎郭攸之、费祎、董允等,此皆良实,志虑忠纯,是以先帝简拔以遗陛下。",
    "愚以为宫中之事,事无大小,悉以咨之,然后施行,必能裨补阙漏,有所广益。",
])
CH3 = chapter("后出师表", [
    "先帝虑汉、贼不两立,王业不偏安,故托臣以讨贼也。",
    "以先帝之明,量臣之才,故知臣伐贼,才弱敌强也。",
    "然不伐贼,王业亦亡。惟坐而待亡,孰与伐之?是故托臣而弗疑也。",
    "受命之日,寝不安席,食不甘味。", "五月渡泸,深入不毛,并日而食。", "臣非不自惜也,顾王业不可得偏安于蜀都。",
])
CH4 = chapter("壬戌之秋", [
    "壬戌之秋,七月既望,苏子与客泛舟游于赤壁之下。",
    "清风徐来,水波不兴。举酒属客,诵明月之诗,歌窈窕之章。",
    "少焉,月出于东山之上,徘徊于斗牛之间。白露横江,水光接天。",
    "纵一苇之所如,凌万顷之茫然。浩浩乎如冯虚御风,而不知其所止;飘飘乎如遗世独立,羽化而登仙。",
])
CH5 = chapter("哀吾生之须臾", [
    "寄蜉蝣于天地,渺沧海之一粟。哀吾生之须臾,羡长江之无穷。",
    "挟飞仙以遨游,抱明月而长终。知不可乎骤得,托遗响于悲风。",
    "苏子曰:客亦知夫水与月乎?逝者如斯,而未尝往也;盈虚者如彼,而卒莫消长也。",
    "盖将自其变者而观之,则天地曾不能以一瞬;自其不变者而观之,则物与我皆无尽也,而又何羡乎!",
])


def write_epub(path: str, entries: list[tuple[str, bytes | str]]) -> None:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        for name, data in entries:
            z.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)


def main() -> None:
    write_epub(os.path.join(HERE, "m1-e2e.epub"), [
        ("META-INF/container.xml", CONTAINER_XML),
        ("nav.xhtml", NAV),
        ("OEBPS/content.opf", OPF),
        ("OEBPS/ch1.xhtml", CH1),
        ("OEBPS/ch2.xhtml", CH2),
        ("OEBPS/ch3.xhtml", CH3),
        ("OEBPS/ch4.xhtml", CH4),
        ("OEBPS/ch5.xhtml", CH5),
        ("OEBPS/cover.png", PNG_1PX),
    ])
    print("m1-e2e.epub", os.path.getsize(os.path.join(HERE, "m1-e2e.epub")), "bytes")


if __name__ == "__main__":
    main()
