# 测试 fixtures

四个 EPUB 测试文件,由 `generate_fixtures.py` 生成(改动后重跑 `python3 generate_fixtures.py` 再提交):

| 文件 | 内容 | 对应行为 |
|---|---|---|
| `normal.epub` | 正常书:4 章正文 + nav 目录(与 OPF 不同目录)+ PNG 封面;覆盖图片/表格/脚注(含嵌套)/代码块/linear="no"/无标题章场景 | 上传 200,清洗入库 |
| `corrupt.epub` | zip 头合法但尾部截断 | 400 文件损坏 |
| `drm.epub` | 合法 EPUB + `META-INF/encryption.xml`(AES 内容加密) | 400 疑似 DRM |
| `font-obfuscated.epub` | 合法 EPUB + 仅字体混淆(IDPF 白名单算法) | 正常导入,不误拦 |

`normal.epub` 覆盖的边界:nav.xhtml 在 spine 中且与 OPF 不同目录(目录本体不入库、href 相对 nav 解析);ch4 标记 `linear="no"` 不入库;ch5 无标题不在目录中(标题 NULL);ch3 含嵌套脚注(只取最外层)。
