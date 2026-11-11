// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File

/** 将已校验安装包交给系统会话，避免依赖厂商安装器组件名及外部文件读取权限。 */
internal object UpdateInstallSession {
    /** 只提取标准错误代号；系统原始正文可能包含路径，不写入日志或提示。 */
    internal fun failureText(status: Int, legacy: Int?, detail: String?): String {
        val reasons = mapOf(
            "INSTALL_FAILED_USER_RESTRICTED" to "系统限制了本应用发起安装",
            "INSTALL_FAILED_VERIFICATION_FAILURE" to "系统安装验证未通过",
            "INSTALL_FAILED_VERIFICATION_TIMEOUT" to "系统安装验证超时",
            "INSTALL_FAILED_NO_MATCHING_ABIS" to "安装包架构不匹配",
            "INSTALL_FAILED_OLDER_SDK" to "系统版本低于安装包要求",
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE" to "已安装应用与新版签名不兼容",
            "INSTALL_FAILED_VERSION_DOWNGRADE" to "系统拒绝安装较低版本",
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" to "系统安装空间不足",
            "INSTALL_FAILED_MISSING_SHARED_LIBRARY" to "系统缺少安装包要求的共享库",
        )
        val legacySymbols = mapOf(
            -111 to "INSTALL_FAILED_USER_RESTRICTED",
            -22 to "INSTALL_FAILED_VERIFICATION_FAILURE",
            -21 to "INSTALL_FAILED_VERIFICATION_TIMEOUT",
            -113 to "INSTALL_FAILED_NO_MATCHING_ABIS",
            -12 to "INSTALL_FAILED_OLDER_SDK",
            -7 to "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            -25 to "INSTALL_FAILED_VERSION_DOWNGRADE",
            -4 to "INSTALL_FAILED_INSUFFICIENT_STORAGE",
            -9 to "INSTALL_FAILED_MISSING_SHARED_LIBRARY",
        )
        val symbol = legacySymbols[legacy] ?: Regex("\\bINSTALL_FAILED_[A-Z_]+\\b")
            .findAll(detail.orEmpty()).map { it.value }.firstOrNull { it in reasons }
        val reason = symbol?.let(reasons::get) ?: when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了安装"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "系统安装已取消"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装包与已安装应用冲突"
            PackageInstaller.STATUS_FAILURE_INVALID -> "系统判定安装包无效"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "系统安装空间不足"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "系统报告安装不兼容，具体原因尚未确认"
            else -> "系统安装未完成"
        }
        val codes = listOfNotNull("状态 $status", legacy?.let { "底层码 $it" }, symbol).joinToString("，")
        return "$reason（$codes）。可用 U 盘手动覆盖安装，保留本机记录"
    }

    fun submit(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallResultActivity::class.java)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                // 新系统只在应用可见时委托确认页启动能力，不用于后台打扰。
                val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                        else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                }.toBundle() else null
                val pending = PendingIntent.getActivity(context, id, callback, flags, options)
                context.getSharedPreferences("update_install_session", 0).edit().putInt("active", id).commit()
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("安装准备已取消")
                session.commit(pending.intentSender)
            }
            return id
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            context.getSharedPreferences("update_install_session", 0).edit().remove("active").apply()
            throw error
        }
    }
}

/** 不对外导出；只有系统持有的显式回调才能进入安装确认流程。 */
class UpdateInstallResultActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val preferences = getSharedPreferences("update_install_session", 0)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || preferences.getInt("active", -1) != id) {
            finish()
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            val opened = confirmation != null && runCatching { startActivity(confirmation) }.isSuccess
            if (!opened) {
                preferences.edit().remove("active").apply()
                runCatching { packageManager.packageInstaller.abandonSession(id) }
                val message = "系统安装会话无法打开确认页，请继续使用 U 盘安装"
                preferences.edit().putString("result_text", message).apply()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } else {
            preferences.edit().remove("active").apply()
            // 底层错误码可区分厂商拒绝和安装包错误；不保存原始错误正文。
            val legacyKey = "android.content.pm.extra.LEGACY_STATUS"
            val legacy = if (intent.hasExtra(legacyKey)) intent.getIntExtra(legacyKey, 0) else null
            android.util.Log.i("HongguoTV", "Update session result status=$status legacy=$legacy")
            if (status != PackageInstaller.STATUS_SUCCESS) {
                val message = UpdateInstallSession.failureText(
                    status, legacy, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
                preferences.edit().putString("result_text", message).apply()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                preferences.edit().remove("result_text").apply()
            }
        }
        finish()
    }
}
