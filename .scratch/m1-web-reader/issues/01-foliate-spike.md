# 01: foliate-js spike:渲染 + 选中取 CFI 两硬点(48h 时间盒)

**What to build:** 全项目最高风险件最先落地(R-6/D-18)。48 小时时间盒内搭一个裸 spike 页:引入 foliate-js(vendor 源码入库,无 npm 包,ADR-0004),验证两个硬点—— 能在现代浏览器渲染 EPUB 正文并翻页/滚动阅读; 选中一段文字能取出可持久化的 EPUB CFI。产出 spike 结论:过/不过、关键坑位与解法清单(含目录解析可行性、字号/主题可调性观察);不过则定案退 epub.js 并修订 ADR-0004,损失锁定在 48h。同时回答"选中手势能否被 Playwright 稳定驱动",给出结论或替代路径(程序化创建划线),喂给 07 的测试策略。不做渲染抽象层——单引擎,不过整引擎换。

**Blocked by:** None(可立即开始)

**Status:** done(结论:.scratch/m1-web-reader/spike-conclusion.md —— 过;vendor 已入库;手势可全自动驱动)

- [x] 裸 spike 页能打开一本多章 EPUB 并渲染正文(硬点 a)
- [x] 选中文字能取出可持久化的 CFI,刷新后按 CFI 能还原位置(硬点 b)
- [x] spike 结论落档:过/不过、关键坑位与解法清单(目录解析、字号/主题可调性)
- [x] 引擎定案:foliate-js 通过并以 vendor 源码正式入库(apps/web/vendor/foliate-js/)
- [x] “选中手势能否被 Playwright 稳定驱动”结论:能(CDP 拖拽一次成功);备选:程序化 addRange
