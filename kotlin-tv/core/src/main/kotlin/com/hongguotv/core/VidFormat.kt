// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 分集标识的合法格式。
 *
 * 2026-11-27 之前是纯数字（如 `7709114790714543128`）；此后网页详情接口改为
 * `v1_` 前缀的字符串（如 `v1_eBxEgtnBUrqzPSgIyqmpbLEYWCM`），旧的纯数字校验会把
 * 正常分集全部判为「分集数据异常」。
 */
internal object VidFormat {
    val PATTERN = Regex("[0-9A-Za-z_-]{1,64}")

    fun isValid(id: String): Boolean = PATTERN.matches(id)
}
