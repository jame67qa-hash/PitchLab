package com.pitchlab.domain.cv

/**
 * 時間窗滾動緩衝（環形佇列），Phase 3「自動剪輯」的核心數據結構。
 *
 * 用法：分析執行緒每幀 push 一份輕量幀（Bitmap 或 YUV 拷貝），
 * 當 MediaPipe 判定「出手」時調用 [snapshot] 取出觸發點前 N 秒的幀序列，
 * 再與觸發點後 N 秒的後置緩衝拼成 MP4 剪輯區間。
 *
 * 注意：Bitmap 佔用顯存/記憶體，務必用固定容量 + 顯式 recycle 控制上限。
 */
class CircularFrameBuffer<T>(private val windowMillis: Long) {

    data class Entry<T>(val timestampNanos: Long, val payload: T)

    private val entries = ArrayDeque<Entry<T>>()

    val size: Int get() = entries.size

    fun clear(release: ((T) -> Unit)? = null) {
        if (release != null) {
            entries.forEach { release(it.payload) }
        }
        entries.clear()
    }

    /**
     * 推入一幀，並淘汰超出 [windowMillis] 的舊幀。
     * @param release 淘汰舊幀時的釋放回調（Bitmap.recycle 等）
     */
    fun push(timestampNanos: Long, payload: T, release: ((T) -> Unit)? = null) {
        entries.addLast(Entry(timestampNanos, payload))
        val cutoff = timestampNanos - windowMillis * 1_000_000L
        while (entries.isNotEmpty() && entries.first().timestampNanos < cutoff) {
            val removed = entries.removeFirst()
            release?.invoke(removed.payload)
        }
    }

    /** 取回 [timestampNanos] 之前 windowMillis 內的全部幀（觸發點前緩衝） */
    fun snapshotBefore(timestampNanos: Long): List<Entry<T>> {
        val cutoff = timestampNanos - windowMillis * 1_000_000L
        return entries.filter { it.timestampNanos >= cutoff && it.timestampNanos <= timestampNanos }
    }

    /** 取回全窗口內容，用於導出調試 */
    fun snapshotAll(): List<Entry<T>> = entries.toList()
}
