# 03: EPUB 上传全链路(正常书)

**What to build:** M0 的核心 tracer bullet:curl 上传一本正常 EPUB,后端**同步**解析出元数据、章节(仅有正文的内容文件,按阅读顺序 seq 平铺)、章节纯文本(按清洗口径:丢图、表格拍平为文本、脚注并入章末、代码块原样保留)与封面,book + chapter 入库,书源文件与封面落盘到服务器目录(进 gitignore),响应返回完整元数据。实现中通过解析 spike 定稿清洗的可执行细则并落档。完成后可演示:上传 200,库里查得到行、盘上找得到文件。

**Blocked by:** 01(后端骨架 tracer:Spring Boot + 鉴权 + Flyway + Testcontainers)

**Status:** ready-for-agent

- [ ] 上传正常 EPUB 返回 200,响应含完整元数据(标题、作者、语言、章节概览、file_hash 等),前端可直接展示导入结果
- [ ] chapter 仅收录"有正文的内容文件"并按阅读顺序 seq 平铺;嵌套目录不入库(D-40)
- [ ] chapter.content 按清洗口径入库:丢图、表格拍平为文本、脚注并入章末、代码块原样保留(D-40);细则经 spike 定稿并落档
- [ ] 书源文件与封面落盘到 gitignore 的服务器目录,后续各端可按需下载、列表可显示封面
- [ ] 解析走同步路径,无异步任务(D-41)
- [ ] 集成测试(正常 EPUB fixture):断言响应、book/chapter 行、磁盘文件三者齐备
