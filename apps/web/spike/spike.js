// M1-01 spike 逻辑:验证 (a) 渲染+翻页/滚动 (b) 选中取 CFI + 按 CFI 还原位置;
// 附带观察:嵌套目录解析、字号/主题可调性、高亮绘制(为 07 划线铺路)。
import '/foliate-js/view.js'
import { makeBook } from '/foliate-js/view.js'
import { Overlayer } from '/foliate-js/overlayer.js'

const $ = id => document.getElementById(id)
const view = $('view')

const state = {
    lastCFI: null,
    selCFI: null,
    fontSize: 100, // 百分比
}

// ---- 打开书 ----

const openBook = async source => {
    const book = await makeBook(source)
    await view.open(book)
    // 硬点 b 后半:localStorage 里的 CFI 恢复位置(刷新还原)
    await view.init({ lastLocation: localStorage.getItem('spike-last-cfi') || undefined })
    renderTOC(book.toc)
    applyStyles()
}

// ?book=<相对路径> 直接打开(供自动化/浏览器驱动);否则等用户选文件
const params = new URLSearchParams(location.search)
const bookParam = params.get('book')
if (bookParam) openBook(new URL(bookParam, location.href).href).then(
    () => console.info('[spike] opened', bookParam),
    e => showError(e, '打开书籍失败'))
$('file').addEventListener('change', e => {
    const f = e.target.files[0]
    if (f) openBook(f).catch(err => showError(err, '打开书籍失败'))
})

// ---- 硬点 a:渲染 + 翻页/滚动 ----

view.addEventListener('relocate', e => {
    const { fraction, section, cfi } = e.detail
    state.lastCFI = cfi
    $('cfi').textContent = cfi
    $('percent').textContent = fraction == null ? '-' : `${Math.round(fraction * 100)}%`
    $('section').textContent = section ? `${section.current + 1} / ${section.total}` : '-'
    localStorage.setItem('spike-last-cfi', cfi) // 自动记录:刷新后还原
})
view.addEventListener('error', e => showError(e.detail, '渲染错误'))

$('prev').addEventListener('click', () => view.prev())
$('next').addEventListener('click', () => view.next())
$('flow').addEventListener('change', e => {
    view.renderer.setAttribute('flow', e.target.value)
    // 切换 flow 后重放当前 CFI,避免位置漂移
    if (state.lastCFI) view.goTo(state.lastCFI)
})
$('restore').addEventListener('click', () => {
    const cfi = localStorage.getItem('spike-last-cfi')
    if (cfi) view.goTo(cfi).then(() => console.info('[spike] restored', cfi))
})

// 键盘翻页
document.addEventListener('keydown', e => {
    if (e.key === 'ArrowLeft') view.prev()
    if (e.key === 'ArrowRight') view.next()
})

// ---- 硬点 b:选中文字 → CFI ----

view.addEventListener('load', ({ detail: { doc } }) => {
    const onSelectionChange = () => {
        const sel = doc.getSelection()
        if (!sel || sel.isCollapsed || sel.rangeCount === 0) return
        const range = sel.getRangeAt(0)
        const contents = view.renderer.getContents()
        const hit = contents.find(({ doc: d }) => d === range.startContainer.ownerDocument)
        if (!hit) return
        try {
            const cfi = view.getCFI(hit.index, range)
            state.selCFI = cfi
            $('sel-cfi').textContent = cfi
            $('highlight').disabled = false
            console.info('[spike] selection CFI', cfi, '→', sel.toString().slice(0, 40))
        } catch (e) {
            console.error('[spike] getCFI failed', e)
        }
    }
    doc.addEventListener('selectionchange', onSelectionChange)
})

// ---- 高亮绘制(验证 overlayer,为 07 划线 UX 铺路) ----

view.addEventListener('draw-annotation', e => {
    const { draw, annotation } = e.detail
    draw(Overlayer.highlight, { color: annotation.color ?? 'yellow' })
})
$('highlight').addEventListener('click', () => {
    if (state.selCFI) view.addAnnotation({ value: state.selCFI, color: 'yellow' })
})

// ---- 目录:嵌套结构直接来自 EPUB 原文件 ----

const renderTOC = toc => {
    const nav = $('toc')
    nav.replaceChildren()
    const items = toc ?? [] // nav 缺失的书 toc 为 undefined(fixture 场景),不阻断
    const build = items2 => {
        const ul = document.createElement('ul')
        for (const item of items2) {
            const li = document.createElement('li')
            const a = document.createElement('a')
            a.textContent = item.label ?? '(无标题)'
            a.addEventListener('click', () => view.goTo(item.href))
            li.append(a)
            if (item.subitems?.length) li.append(build(item.subitems))
            ul.append(li)
        }
        return ul
    }
    nav.append(build(items))
    console.info('[spike] toc items rendered:', items.length)
}

// ---- 字号/主题:只作用于端上,验证可调 + localStorage 保留 ----

const THEMES = {
    light: { fg: '#1a1a1a', bg: '#ffffff' },
    sepia: { fg: '#5b4636', bg: '#f4ecd8' },
    dark:  { fg: '#ddd',    bg: '#202020' },
}

const applyStyles = () => {
    $('font-size-label').textContent = `${state.fontSize}%`
    localStorage.setItem('spike-font-size', String(state.fontSize))
    const theme = $('theme').value
    localStorage.setItem('spike-theme', theme)
    document.body.dataset.theme = theme
    const { fg, bg } = THEMES[theme]
    // 内容 iframe 注入:字号 + 前景/背景色(!important 压过书籍自带样式)
    view.renderer?.setStyles(`
        :root { font-size: ${state.fontSize}% !important; color: ${fg}; background: ${bg}; }
    `)
}

$('font-plus').addEventListener('click', () => { state.fontSize += 10; applyStyles() })
$('font-minus').addEventListener('click', () => { state.fontSize -= 10; applyStyles() })
$('theme').addEventListener('change', applyStyles)

state.fontSize = Number(localStorage.getItem('spike-font-size')) || 100
$('theme').value = localStorage.getItem('spike-theme') || 'light'

// 供驱动方(Python 脚本)读结果的钩子
window.spikeState = state
console.info('[spike] ready')

function showError(err, prefix) {
    console.error('[spike]', prefix, err)
    const el = document.createElement('pre')
    el.style.color = 'crimson'
    el.textContent = `${prefix}: ${err?.message ?? err}`
    document.body.prepend(el)
}
