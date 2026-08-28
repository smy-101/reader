# 04: 后端:书源文件下载端点

**What to build:** 阅读器的原料供给。按书籍 id 流式返回书源文件(EPUB 原文件),供端上渲染引擎程序化带 token 拉取——token 走请求头,不以 URL 查询参数传递。与其他接口一样受静态 token 拦截。Seam A(既有 HTTP × Testcontainers)集成测试:下载内容与上传时落盘的原文件一致;书不存在返回 404 可读文案;无/错 token 一律 401。

**Blocked by:** None(可立即开始)

**Status:** done

- [x] 按书 id 可下载书源文件,内容与上传时落盘的原文件一致
- [x] 书不存在 → 404 可读文案;无/错 token → 401,与其他接口行为一致
- [x] token 仅经请求头传递,不进 URL 参数
- [x] Seam A 集成测试覆盖上述行为,`mvn test` 全绿
