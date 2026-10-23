package com.lonx.lyrico.plugin.source.native

import kotlinx.coroutines.delay

/**
 * 票制限流器（移植自 MusicOrganizer 的 throttle 实现）。
 *
 * 关键点是**锁内只预约发车时间，锁外 sleep**：并发调用时，各线程拿到
 * now、now+interval、now+2*interval… 依次出发，而不是排队等「上一个请求跑完 + interval」。
 * 后者在批量匹配（多首歌并发查同一个源）时会把延迟放大成 N 倍。
 *
 * 各源官方要求的间隔：MusicBrainz 1 req/s、LRCLIB 约 3 req/s。
 */
class NativeRateLimiter(
    private val minIntervalMs: Long
) {
    private var nextSlot = 0L

    suspend fun acquire() {
        val wait = synchronized(this) {
            val now = System.currentTimeMillis()
            val slot = maxOf(now, nextSlot)
            nextSlot = slot + minIntervalMs
            slot - now
        }
        if (wait > 0L) delay(wait)
    }
}
