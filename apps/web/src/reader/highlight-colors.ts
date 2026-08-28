/** 划线颜色板(前端固定可选色;后端只存字符串原样返回)。 */
export const HIGHLIGHT_COLORS = [
    {id: 'yellow', label: '黄', hex: '#ffd23f'},
    {id: 'green', label: '绿', hex: '#7ee787'},
    {id: 'blue', label: '蓝', hex: '#79c0ff'},
    {id: 'red', label: '红', hex: '#ff8183'},
    {id: 'purple', label: '紫', hex: '#d2a8ff'},
] as const

export type HighlightColorId = typeof HIGHLIGHT_COLORS[number]['id']

export function colorHex(id: string | null | undefined): string {
    return HIGHLIGHT_COLORS.find(c => c.id === id)?.hex ?? HIGHLIGHT_COLORS[0].hex
}

/** 划线文字快照的列表展示截断。 */
export function snippet(text: string, max = 60): string {
    return text.length > max ? text.slice(0, max) + '…' : text
}
