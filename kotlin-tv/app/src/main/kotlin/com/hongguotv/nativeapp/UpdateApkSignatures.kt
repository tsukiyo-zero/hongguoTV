// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException

/** Keep archive certificate collection compatible with older framework implementations. */
@Suppress("DEPRECATION")
internal object UpdateApkSignatures {
    fun flags(): Int = PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private fun signers(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        // Some OEM frameworks populate only the legacy field. Never replace a nonempty
        // modern signer set with legacy signing history when the modern set mismatches.
        val certificates = modern?.takeIf { it.isNotEmpty() } ?: info.signatures
        return certificates?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    fun verify(installed: PackageInfo, candidate: PackageInfo) {
        val current = signers(installed)
        val downloaded = signers(candidate)
        if (current.isEmpty() || downloaded.isEmpty())
            throw IOException("无法读取安装包签名，已阻止安装；可从官方发布页下载并手动覆盖安装")
        if (current != downloaded) throw IOException("新版签名不匹配，已阻止安装")
    }
}
