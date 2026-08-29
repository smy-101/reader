/**
 * 跨书引用跳转信号(S4-03):书库全局 AI 面板点击引用 → 应用顶层状态传递 →
 * 打开对应书进入阅读器 → 就绪后执行 S3 同款定位(章 + 摘录,未命中停章首)。
 */
export interface PendingCitationJump {
    bookId: number
    chapterId?: number
    excerpt?: string
}
