# 测试 fixtures

三个 EPUB 测试文件,由 `generate_fixtures.py` 生成(改动后重跑 `python3 generate_fixtures.py` 再提交):

| 文件 | 内容 | 对应行为 |
|---|---|---|
| `normal.epub` | 正常书:3 章正文 + nav 目录 + PNG 封面;覆盖图片/表格/脚注/代码块四类清洗场景 | 上传 200,清洗入库 |
| `corrupt.epub` | zip 头合法但尾部截断 | 400 文件损坏 |
| `drm.epub` | 合法 EPUB + `META-INF/encryption.xml` | 400 疑似 DRM |

`normal.epub` 的 nav.xhtml 出现在 spine 中:它是目录本体(EPUB3 nav 属性),按 D-40 不入库为章节;
清洗后无正文的 spine 项(如纯图片封面页)同样不入库(ADR-0005)。
