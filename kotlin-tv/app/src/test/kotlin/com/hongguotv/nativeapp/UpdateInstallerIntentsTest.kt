// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 安装入口候选：小米等定制电视会摘掉隐式匹配，只保留组件直达；
 * Android 6 还可能需要 file:// 形式。这里只校验候选集合的构成。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class UpdateInstallerIntentsApi23Test {
    private val content = Uri.parse("content://io.github.tsukiyo_zero.guoyingtv.updates/updates/app.apk")
    private val file = Uri.parse("file:///data/user/0/io.github.tsukiyo_zero.guoyingtv/cache/updates/app.apk")

    @Test
    fun api23IncludesLegacyActionComponentsAndFileUri() {
        val intents = AppUpdater.installerIntents(content, file)
        assertTrue(intents.any { it.action == Intent.ACTION_INSTALL_PACKAGE })
        assertTrue(intents.any { it.action == Intent.ACTION_VIEW })
        assertTrue(intents.any { it.data?.scheme == "file" })
        assertTrue(intents.any { it.component?.packageName == "com.miui.packageinstaller" })
        assertTrue(intents.all { it.type == "application/vnd.android.package-archive" })
    }

    @Test
    fun everyCandidateCarriesPackageMimeType() {
        val intents = AppUpdater.installerIntents(content, null)
        assertTrue(intents.isNotEmpty())
        assertTrue(intents.none { it.type == null })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class UpdateInstallerIntentsApi26Test {
    private val content = Uri.parse("content://io.github.tsukiyo_zero.guoyingtv.updates/updates/app.apk")

    @Test
    fun api26DropsLegacyActionAndFileUri() {
        val intents = AppUpdater.installerIntents(content, null)
        assertFalse(intents.any { it.action == Intent.ACTION_INSTALL_PACKAGE })
        assertTrue(intents.any { it.action == Intent.ACTION_VIEW })
        assertFalse(intents.any { it.data?.scheme == "file" })
    }
}
