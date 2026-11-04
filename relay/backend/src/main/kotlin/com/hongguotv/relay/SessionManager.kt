// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.nio.file.Path

/** 单活跃会话管理：创建/查找/停止/空闲回收。 */
class SessionManager(private val workRoot: Path) {
    class BusyException(val sessionId: String) : IllegalStateException("已有活跃会话")

    private val lock = Any()
    private var current: Session? = null

    fun create(id: String, build: (dir: Path) -> Session): Session =
        synchronized(lock) {
            val running = current?.takeIf { it.alive }
            if (running != null) {
                // 电视端异常退出可能没发 DELETE：超过 30s 无人拉流视为遗留会话，允许新会话直接接管。
                val idleFor = System.currentTimeMillis() - running.lastAccessMs
                if (idleFor <= STALE_MS) throw BusyException(running.id)
            }
            current?.let { runCatching { it.stop() } }
            build(workRoot.resolve(id)).also { current = it }
        }

    fun find(id: String): Session? = synchronized(lock) { current?.takeIf { it.id == id } }

    fun currentOrNull(): Session? = synchronized(lock) { current }

    fun stop(id: String): Boolean =
        synchronized(lock) {
            current?.takeIf { it.id == id }?.let { it.stop(); true } ?: false
        }

    fun sweepIdle(nowMs: Long, timeoutMs: Long) {
        synchronized(lock) {
            current?.takeIf { it.alive && nowMs - it.lastAccessMs > timeoutMs }?.let { it.stop() }
        }
    }

    fun shutdown() {
        synchronized(lock) { current?.let { runCatching { it.stop() } } }
    }

    companion object {
        /** 会话超过该时长无人拉流即视为遗留（电视端异常退出未发 DELETE），允许新会话接管。 */
        const val STALE_MS = 30_000L
    }
}
