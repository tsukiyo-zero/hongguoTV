// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.relay

import java.nio.file.Path

/** 单活跃会话管理：创建/查找/停止/空闲回收。 */
class SessionManager(private val workRoot: Path) {
    class BusyException : IllegalStateException("已有活跃会话")

    private val lock = Any()
    private var current: Session? = null

    fun create(id: String, build: (dir: Path) -> Session): Session =
        synchronized(lock) {
            current?.takeIf { it.alive }?.let { throw BusyException() }
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
}
