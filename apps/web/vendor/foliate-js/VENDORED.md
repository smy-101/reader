# Vendored: foliate-js

- 上游:https://github.com/johnfactotum/foliate-js
- 版本:commit `78914aef4466eb960965702401634c2cb348e9b1`(2026-05-01,main 浅克隆)
- 引入方式:源码 vendor 入库(ADR-0004 / D-18;无 npm 包,升级 = 手动替换本目录并回归)
- 裁剪口径:仅保留 EPUB 渲染闭包(view/epub/epubcfi/paginator/fixed-layout/overlayer/progress/
  text-walker/search/tts + vendor/zip.js、vendor/fflate.js)。
  排除:pdfjs(13MB,PDF 不在本项目范围)、mobi/fb2/cbz/pdf/dict/opds 等非 EPUB 格式模块、
  rollup/tests/ui/演示页(reader.html/reader.js)。
  `view.js` 对被排除模块只有按需动态 import,打开 EPUB 不会触发;若未来支持其他格式需补齐对应模块。
