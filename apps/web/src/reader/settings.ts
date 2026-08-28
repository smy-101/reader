/** 阅读设置(FR-201):纯端上偏好,仅存 localStorage,不跨端同步、不进任何后端接口。 */
export interface ReaderSettings {
    /** 字号百分比(100 = 默认) */
    fontSize: number
    theme: 'light' | 'sepia' | 'dark'
    flow: 'paginated' | 'scrolled'
}

const KEY = 'reader-settings'

export const DEFAULT_SETTINGS: ReaderSettings = {fontSize: 100, theme: 'light', flow: 'paginated'}

export function loadSettings(): ReaderSettings {
    try {
        const raw = localStorage.getItem(KEY)
        if (!raw) return DEFAULT_SETTINGS
        const parsed = JSON.parse(raw) as Partial<ReaderSettings>
        return {
            fontSize: typeof parsed.fontSize === 'number' && parsed.fontSize >= 50 && parsed.fontSize <= 300
                ? parsed.fontSize : 100,
            theme: parsed.theme === 'sepia' || parsed.theme === 'dark' ? parsed.theme : 'light',
            flow: parsed.flow === 'scrolled' ? 'scrolled' : 'paginated',
        }
    } catch {
        return DEFAULT_SETTINGS
    }
}

export function saveSettings(settings: ReaderSettings): void {
    localStorage.setItem(KEY, JSON.stringify(settings))
}

/** 主题色板:宿主页与内容 doc 同步注入(setStyles)。 */
export const THEME_COLORS: Record<ReaderSettings['theme'], { fg: string; bg: string }> = {
    light: {fg: '#1f2328', bg: '#ffffff'},
    sepia: {fg: '#5b4636', bg: '#f4ecd8'},
    dark: {fg: '#d6d8da', bg: '#202024'},
}
