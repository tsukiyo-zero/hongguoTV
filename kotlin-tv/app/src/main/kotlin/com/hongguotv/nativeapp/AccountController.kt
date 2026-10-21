// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.Toast
import com.hongguotv.core.*
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay in an Android Keystore encrypted partition, outside library exports. */
private class AccountStore(context: Context) {
    private val prefs=context.getSharedPreferences("cloud_account_secure",Context.MODE_PRIVATE)
    private val alias="hongguotv.cloud.account.v1"
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): AccountCredentials? {
        val raw=prefs.getString("session",null) ?: return null
        val bytes=Base64.decode(raw,Base64.NO_WRAP)
        require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return AccountCredentials.fromJson(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    fun save(credentials: AccountCredentials) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        val raw=cipher.iv+cipher.doFinal(credentials.toJson().toByteArray())
        check(prefs.edit().putString("session",Base64.encodeToString(raw,Base64.NO_WRAP)).commit()) { "保存登录态失败" }
    }
    fun clear() { check(prefs.edit().clear().commit()) { "清除登录态失败" } }
}

/** Cloud history is kept separate from guest records; only explicit playback creates local history. */
class AccountController(private val activity: Activity,private val play: (CloudProgress)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val http=okhttp3.OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
        .callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val client=CloudAccountClient(http)
    private val store=AccountStore(activity)
    @Volatile private var generation=0
    @Volatile private var closed=false
    private var credentials: AccountCredentials?=null
    private var profile: CloudProfile?=null
    private var history=emptyList<CloudProgress>()
    private var status="未登录"
    private var busy=false
    private var lastUpload=0L
    private var lastEpisode=""
    private var pending: Pair<CloudProgress,Int>?=null
    private var lastProgress: Pair<CloudProgress,Int>?=null
    private var draining=false
    private var dialog: AlertDialog?=null
    var onHistoryChanged: (() -> Unit)?=null
    val signedIn get()=credentials!=null
    val accountLabel get()=profile?.nickname ?: if(signedIn) "红果账号" else "未登录"
    val historyStatus get()=status
    val historyLoading get()=busy
    val historyScope get()=generation
    fun historyRows(): List<CloudProgress> = if(signedIn) history.toList() else emptyList()
    fun refreshHistory() { refresh(false) }
    private fun changed() { onHistoryChanged?.invoke() }
    val showing get() = dialog?.isShowing==true
    init {
        worker.execute {
            val saved=runCatching { store.read() }
            main.post {
                if(closed) return@post
                credentials=saved.getOrNull()
                status=if(credentials!=null) "登录态已恢复，打开账号页面刷新云端记录" else if(saved.isFailure) "登录态无法读取，请重新登录" else "未登录"
                changed()
                if(credentials!=null) refresh(false)
            }
        }
    }
    private fun toast(value: String) { if(!closed) Toast.makeText(activity,value,Toast.LENGTH_LONG).show() }
    private fun safeError(error: Throwable) = when(error) {
        is java.net.SocketTimeoutException -> "账号接口连接超时，请稍后重试"
        is java.net.UnknownHostException -> "无法连接账号接口，请检查网络"
        else -> error.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(150).ifBlank { "账号操作失败" }
    }
    fun progress(id: String): CloudProgress? = if(credentials!=null) history.firstOrNull { it.series.id==id } else null
    private fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss(); dialog=builder.create(); dialog?.show()
    }
    fun show() {
        if(closed) return
        if(busy) { toast("账号操作进行中，请稍候"); return }
        val signed=credentials!=null
        val choices=if(signed) arrayOf("云端继续观看（最近 50 部）","刷新账号和云端记录","退出红果账号") else arrayOf("使用红果 App 扫码登录","手机号 + 验证码登录")
        showDialog(AlertDialog.Builder(activity).setTitle(profile?.let { "红果账号 · ${it.nickname}" } ?: "红果账号（测试功能）")
            .setItems(choices) { _,index ->
                if(!signed) { if(index==0) qrLogin() else phone() } else when(index) { 0->showHistory(); 1->refresh(true); 2->logout() }
            }.setNeutralButton("同步状态") { _,_->
                showDialog(AlertDialog.Builder(activity).setTitle("账号同步状态").setMessage("$status\n\n登录后，电视播放的进度会自动上传。手机记录可从「云端继续观看」打开。平台接口可能变化，首次使用请对照手机核对集数和时间。\n本机历史与云端记录分别保留；退出登录会停止上传。").setPositiveButton("关闭",null))
            }.setNegativeButton("关闭",null))
    }
    private fun input(title: String,numeric: Boolean,error: String?=null,submit: (String)->Unit) {
        val field=EditText(activity).apply { isSingleLine=true; inputType=if(numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_PHONE }
        val builder=AlertDialog.Builder(activity).setTitle(title).setView(field).setNegativeButton("取消",null).setPositiveButton("确认",null)
        if(error!=null) builder.setMessage(error)
        showDialog(builder)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val value=field.text.toString().trim(); if(value.isBlank()) return@setOnClickListener
            dialog?.dismiss(); submit(value)
        }
    }
    private fun phone() {
        input("手机号（确认后发送登录验证码）",false) { raw ->
            val mobile=runCatching { PhoneLogin.parse(raw) }.getOrElse { toast(safeError(it)); return@input }
            val login=WebLogin(http)
            task({ login.send(mobile) },{ sent -> code(login,sent.maskedMobile) },{ failure ->
                showDialog(AlertDialog.Builder(activity).setTitle("短信登录未完成").setMessage(safeError(failure))
                    .setPositiveButton("改用扫码") { _,_->qrLogin() }.setNegativeButton("返回",null))
            })
        }
    }
    private fun code(login: WebLogin,masked: String,error: String?=null) {
        input("输入发送至 $masked 的验证码",true,error) { value ->
            if(!value.matches(Regex("[0-9]{4,8}"))) { code(login,masked,"请输入 4—8 位验证码"); return@input }
            task({
                validateAccount(login.verify(value)).also { store.save(it.first) }
            },{ (next,account) ->
                acceptAccount(next,account)
            },{ failure ->
                if(failure is PhoneLogin.Failure && !failure.canRetryCode) {
                    showDialog(AlertDialog.Builder(activity).setTitle("登录未完成").setMessage(safeError(failure))
                        .setPositiveButton("返回账号页") { _,_->show() }
                        .setNeutralButton("改用扫码") { _,_->qrLogin() })
                } else code(login,masked,safeError(failure))
            })
        }
    }
    private fun validateAccount(web: AccountCredentials): Pair<AccountCredentials,CloudProfile> {
        val device=client.registerDevice() ?: throw java.io.IOException("账号设备初始化失败")
        val next=web.copy(deviceId=device.deviceId,installId=device.installId)
        val account=client.profile(next)
        return next to account
    }
    private fun acceptAccount(next: AccountCredentials,account: CloudProfile) {
        credentials=next; profile=account; history=emptyList(); generation++
        status="已登录，正在读取云端记录"; lastUpload=0; lastEpisode=""; lastProgress=null; pending=null; draining=false
        changed(); toast("已登录 ${account.nickname}"); refresh(true)
    }
    private fun qrLogin() {
        val login=WebLogin(http)
        task({ login.qr() },{ qr ->
            val bitmap=android.graphics.BitmapFactory.decodeByteArray(qr.image,0,qr.image.size)
            if(bitmap==null) { toast("登录二维码无法显示，请重试"); return@task }
            val size=(300*activity.resources.displayMetrics.density).toInt()
            val image=android.widget.ImageView(activity).apply {
                setImageBitmap(bitmap); adjustViewBounds=true; setBackgroundColor(android.graphics.Color.WHITE)
                layoutParams=android.widget.LinearLayout.LayoutParams(size,size).apply { gravity=android.view.Gravity.CENTER_HORIZONTAL }
            }
            val panel=android.widget.LinearLayout(activity).apply {
                orientation=android.widget.LinearLayout.VERTICAL; setPadding(24,16,24,16)
                addView(image)
                addView(android.widget.TextView(activity).apply {
                    text="用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录"
                    textSize=18f; gravity=android.view.Gravity.CENTER; setPadding(0,12,0,0)
                })
            }
            showDialog(AlertDialog.Builder(activity).setTitle("红果官方扫码登录").setView(panel).setNegativeButton("取消",null))
            val current=dialog ?: return@task
            val token=generation
            val deadline=android.os.SystemClock.elapsedRealtime()+qr.expiresIn.coerceIn(1,300)*1000L
            var cancelled=false
            busy=true
            status="等待手机确认扫码登录"
            current.setOnDismissListener {
                cancelled=true; login.cancel()
                if(token==generation) { busy=false; status="已取消扫码登录" }
            }
            // Android dispatches OnDismiss through a Handler. Finish the QR operation now;
            // a delayed dismissal must not keep the save task busy or cancel its successor.
            fun closeQr() {
                cancelled=true
                current.setOnDismissListener(null)
                login.cancel()
                if(token==generation) busy=false
                current.dismiss()
            }
            fun poll() {
                if(cancelled || closed || token!=generation) return
                if(android.os.SystemClock.elapsedRealtime()>=deadline) {
                    closeQr(); status="二维码已过期，请重新扫码"
                    showDialog(AlertDialog.Builder(activity).setTitle("二维码已过期").setMessage("请刷新二维码，再用手机红果 App 扫码确认。")
                        .setPositiveButton("刷新二维码") { _,_->qrLogin() }.setNegativeButton("取消",null))
                    return
                }
                worker.execute {
                    val result=runCatching { if(login.poll()) validateAccount(login.credentials()) else null }
                    main.post {
                        if(cancelled || closed || token!=generation) return@post
                        result.onSuccess { account ->
                            if(account!=null) {
                                closeQr()
                                status="扫码已确认，正在保存登录状态"
                                task({ store.save(account.first); account },{ acceptAccount(it.first,it.second) },{ failure ->
                                    showDialog(AlertDialog.Builder(activity).setTitle("登录状态保存失败").setMessage(safeError(failure))
                                        .setPositiveButton("重新扫码") { _,_->qrLogin() }.setNegativeButton("返回账号页") { _,_->show() })
                                })
                            } else main.postDelayed({ poll() },2000)
                        }.onFailure {
                            closeQr(); status=safeError(it)
                            showDialog(AlertDialog.Builder(activity).setTitle("扫码登录未完成").setMessage(status).setPositiveButton("重新扫码") { _,_->qrLogin() }
                                .setNegativeButton("返回账号页") { _,_->show() })
                        }
                    }
                }
            }
            main.postDelayed({ poll() },1500)
        })
    }
    private fun <T> task(action: ()->T,success: (T)->Unit,failure: (Throwable)->Unit={ toast(safeError(it)) }) {
        if(busy || closed) return
        busy=true; val token=generation; toast("正在连接红果账号…")
        worker.execute {
            if(closed || token!=generation) return@execute
            val result=runCatching(action)
            main.post {
                if(closed || token!=generation) return@post
                busy=false
                result.onSuccess(success).onFailure { status=safeError(it); failure(it) }
            }
        }
    }
    private fun refresh(showResult: Boolean) {
        val account=credentials ?: return
        if(busy || closed) return
        status="正在读取账号观看历史…"
        task({ client.profile(account) to client.history(account) },{ (user,rows) ->
            profile=user
            history=rows.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }.take(50)
            status="已读取 ${rows.size} 部云端观看记录"
            changed()
            if(showResult) { toast(status); show() }
        },{ changed(); toast(safeError(it)) })
        changed()
    }
    private fun showHistory() {
        if(history.isEmpty()) { toast("暂无已读取的云端记录，请先刷新"); return }
        val snapshot=history
        showDialog(AlertDialog.Builder(activity).setTitle("云端继续观看")
            .setItems(snapshot.map { "${it.series.title} · 第 ${it.episodeIndex+1} 集 · ${it.position/1000} 秒" }.toTypedArray()) { _,index -> play(snapshot[index]) }
            .setNegativeButton("关闭",null))
    }
    private fun logout() {
        generation++; busy=false; credentials=null; profile=null; history=emptyList(); pending=null; lastProgress=null; draining=false
        http.dispatcher.cancelAll(); status="已退出账号，自动同步已停止"; changed()
        worker.execute { val result=runCatching { store.clear() }; main.post { toast(if(result.isSuccess) status else "清除登录态失败，请重试退出") } }
    }
    fun upload(progress: CloudProgress,episodes: Int,force: Boolean) {
        val account=credentials?.takeIf { profile!=null } ?: return
        val now=android.os.SystemClock.elapsedRealtime()
        if(!force && progress.episodeId==lastEpisode && now-lastUpload<30000) return
        lastUpload=now; lastEpisode=progress.episodeId
        history=(listOf(progress)+history.filterNot { it.series.id==progress.series.id }).take(50)
        changed()
        pending=progress to episodes
        lastProgress=pending
        if(draining) return
        draining=true; drain(account,generation)
    }
    private fun drain(account: AccountCredentials,token: Int) {
        val next=pending
        if(closed || token!=generation) return
        if(next==null) { draining=false; return }
        pending=null
        worker.execute {
            val result=runCatching { if(!closed && token==generation) client.upload(account,next.first,next.second) }
            main.post {
                if(closed) return@post
                if(token==generation) status=if(result.isSuccess) "播放进度已上传（请在手机端核对续播）" else "进度上传失败：${safeError(result.exceptionOrNull()!!)}"
                drain(account,token)
            }
        }
    }
    fun destroy() {
        val account=credentials; val final=lastProgress
        onHistoryChanged=null
        closed=true; generation++; pending=null; dialog?.dismiss(); http.dispatcher.cancelAll()
        worker.execute {
            if(account!=null && final!=null) runCatching { client.upload(account,final.first,final.second) }
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
        worker.shutdown()
    }
}
