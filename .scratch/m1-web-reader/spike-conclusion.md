# foliate-js spike 结论(M1-01)

- 结论:**过**(两个硬点均验证通过,引擎定案 foliate-js,vendor 源码已入库 `apps/web/vendor/foliate-js/`)
- 上游版本:commit `78914aef4466eb960965702401634c2cb348e9b1`(裁剪口径见 vendor 目录 VENDORED.md)
- 验证环境:WSL 静态服务 + Windows Chrome(chrome-devtools MCP 驱动,与 Playwright 同为 CDP 通道)
- 验证日期:2026-08-28(时间盒内完成,远低于 48h 上限)

## 硬点 a:渲染 ✅

- `makeBook(file|url)` → `view.open(book)` → `<foliate-view>` 正常渲染正文;EPUB3 nav 书与缺 nav 书均可打开
- 翻页:`view.prev()/next()`、方向键;滚动模式:`renderer.setAttribute('flow','scrolled')`(切换后需重放 CFI 防位置漂移)
- 目录:`book.toc` 直接是嵌套结构(label/href/subitems),前端渲染即可,无需后端(D-40 口径成立)
- 字号:`renderer.setStyles(':root { font-size: N% !important }')` 生效(100%→120% 实测 16px→19.2px)
- 主题:宿主页 CSS 变量 + setStyles 注入内容前景/背景,亮/暗/纸黄可用
- relocate 事件 detail 含 `cfi / fraction(全书 0-1) / section{current,total}` → 进度上报与接续直接可用

## 硬点 b:选中取 CFI ✅

- 真实手势:CDP 鼠标拖拽跨段选中 iframe 内文字成功,`doc.getSelection()` → `view.getCFI(index, range)` 得到
  区间 CFI(实测 `epubcfi(/6/4!/4,/4/1:18,/6/1:16)`)
- 持久化还原:CFI 存 localStorage → 刷新页面 → `view.init({ lastLocation: cfi })` 精确回到原 CFI、原百分比(实测一致)
- 附带验证:overlayer 高亮绘制可用(`draw-annotation` 事件 + `view.addAnnotation`),07 划线 UX 有底

## 选中手势能否被 Playwright 稳定驱动?——**能,全自动**

- chrome-devtools MCP(同为 CDP)对 iframe 内文本节点的拖拽一次成功;Playwright 的 `mouse`/frame API 能力是超集
- 兜底路径(无需手势):`doc.getSelection().addRange(range)` 同样触发 `selectionchange`,可程序化制造选中
- 结论:E2E(07)选中→划线走全自动手势;程序化路径作为 fixture 化的备选

## 关键坑位与解法(给 05/07/08)

1. `makeBook` 只接受 string/File;传 `URL` 对象会 `NotFoundError`,要传 `url.href`
2. 内容渲染在 **closed shadow DOM 内的 blob: URL iframe**:宿主选择器摸不到内容;iframe 同源,
   经 `getContents()[i].doc` 访问;宿主坐标 = iframe `getBoundingClientRect()` + 内部 `getBoundingClientRect()`
3. 字号/主题必须注入内容 doc(`setStyles` + `!important` 压过书籍自带样式);宿主与内容要同时调
4. 高亮 SVG(overlayer)挂在 paginator shadow DOM,不在 iframe doc 内;经 `getContents()[i].overlayer` 检查
5. `book.toc` 可能为 `undefined`(缺 nav 的书),渲染目录需容错;E2E fixture 记得带 nav.xhtml(可再加 NCX)
6. `selectionchange` 需在 view 的 `load` 事件里挂到每个内容 doc
7. 字体混淆解密走 Web Crypto,仅安全上下文可用:本地/CI 用 localhost 无碍,将来公网部署需 HTTPS(M6 记账)
8. iframe sandbox 警告("allow-scripts + allow-same-origin 可逃逸")是 foliate-js 已知取舍,单用户自部署可接受

## 残留观察(不阻塞)

- 缺 nav 的书无 toc 可渲染(正常,真实书都有 nav 或 NCX;后端章节列表不受影响)
- 切 flow 分页↔滚动后建议重放当前 CFI(已记入坑位 6 条目)
