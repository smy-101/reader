# ADR-0004: EPUB 渲染引擎选 foliate-js(源码 vendor 入库)

- 状态:已接受(2026-02,盘问 R5-Q16);**M1 前置 48h spike 验证中**
- 关联决策:D-18(需求文档 §6.1);关联风险:R-6

## 背景

网页阅读器(M1)需要在浏览器里渲染 EPUB 并支持"选中文字 → 取 EPUB CFI"(划线与 S1 选中即问的定位基础)。备选:epub.js(老牌、npm 有包、社区维护趋缓)与 foliate-js(Foliate 阅读器的 JS 内核,渲染质量好、活跃,但**无 npm 包、文档少、要读源码**)。

## 决策

选 **foliate-js**,以**源码 vendor 进仓库**的方式引入(`apps/web/vendor/foliate-js/`);并以 48h spike 为 M1 硬前置——spike 只验两个硬点:**渲染** 与 **选中取 CFI**,不通过则退 epub.js(D-18)。

## 理由

- 渲染保真与 CFI 正确性是这个项目阅读体验的地基;foliate-js 出身成熟 Linux 阅读器 Foliate,对 EPUB 规范覆盖更全。
- 无 npm 包反而促成 vendor 入库:版本钉死、可读源码排障(R-6),不受上游发版节奏绑架。

## 后果

- 无包管理器升级通道:升级 = 手动替换 vendor 目录并回归测试。
- 学习成本高于开箱即用库;spike 不过的退出路径已锁定(epub.js),损失上限 48h。
