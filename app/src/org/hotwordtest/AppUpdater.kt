package org.hotwordtest

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.Executors

class AppUpdater(private val activity: Activity, private val onState: (UpdateState) -> Unit) {
    private val context = activity.applicationContext
    private val prefs = context.updatePrefs()
    private val main = Handler(Looper.getMainLooper())
    private var offered = ""
    private var reading = false
    private var launchChecked = false
    private val poll = Runnable { readTransfer() }
    private val periodic = Runnable { checkRelease(false) }

    companion object {
        internal var visible: AppUpdater? = null
            private set
        private val transfers = Executors.newSingleThreadExecutor()
    }

    fun resume() {
        visible = this
        ReleaseCheckJob.schedule(context)
        val launching = !launchChecked
        launchChecked = true
        if (launching) checkRelease(false, launching = true)
        if (UpdateTransfer.id(context) >= 0) readTransfer() else if (!launching) checkRelease(false)
    }

    fun pause() {
        if (visible === this) visible = null
        main.removeCallbacks(poll)
        main.removeCallbacks(periodic)
    }

    fun check() {
        if (UpdateTransfer.id(context) < 0) {
            checkRelease(true)
        } else if (prefs.contains("transferError")) {
            transfers.execute {
                UpdateTransfer.clear(context)
                main.post { visible?.checkRelease(true) }
            }
        } else {
            readTransfer()
        }
    }

    private fun checkRelease(manual: Boolean, launching: Boolean = false) {
        if (visible !== this) return
        val transferring = UpdateTransfer.id(context) >= 0
        if (transferring && !launching) return
        if (manual) offered = ""
        val age = System.currentTimeMillis() - prefs.getLong("checked", 0)
        if (!transferring && (manual || launching || age !in 0 until UPDATE_PERIOD))
            onState(UpdateState(UpdatePhase.CHECKING, message = "正在检查GitHub Releases"))
        ReleaseFeed.check(context, manual || launching) { showRelease() }
    }

    internal fun showRelease() {
        if (visible !== this || UpdateTransfer.id(context) >= 0) return
        main.removeCallbacks(periodic)
        val age = (System.currentTimeMillis() - prefs.getLong("checked", 0)).coerceIn(0L, UPDATE_PERIOD)
        main.postDelayed(periodic, (UPDATE_PERIOD - age).coerceAtLeast(1000L))
        val release = ReleaseFeed.cached(context)
        val installed = ReleaseVersion.parse(context.installedInfo().versionName.orEmpty())
        val tag = release?.let { ReleaseVersion.tag(it.tag) }
        val failed = prefs.getBoolean("checkFailed", false)
        val resultMessage = prefs.getString("checkMessage", "尚未检查").orEmpty()
        val state = when {
            release == null -> UpdateState(if (failed) UpdatePhase.ERROR else UpdatePhase.IDLE, message = resultMessage)
            installed == null || tag == null -> UpdateState(UpdatePhase.ERROR, message = "版本号无法比较")
            failed && installed >= tag -> UpdateState(UpdatePhase.ERROR, message = resultMessage)
            installed >= tag -> UpdateState(UpdatePhase.CURRENT, version = release.tag, message = "当前已是最新版本")
            else -> {
                val offer = offered != release.tag
                offered = release.tag
                UpdateState(UpdatePhase.AVAILABLE, version = release.tag, notes = release.notes,
                    message = if (failed) "上次检查发现${release.tag}，本次未能刷新" else "发现新版本${release.tag}",
                    total = release.size, offer = offer)
            }
        }
        onState(state)
    }

    fun download(expectedTag: String? = null) {
        val release = UpdateTransfer.release(context) ?: ReleaseFeed.cached(context)
        if (release == null || (expectedTag != null && release.tag != expectedTag)) {
            onState(UpdateState(UpdatePhase.ERROR, message = "发布信息已变更，请重新检查更新"))
            return
        }
        val installed = ReleaseVersion.parse(context.installedInfo().versionName.orEmpty()) ?: return
        if (ReleaseVersion.tag(release.tag)?.let { it > installed } != true) return
        transfers.execute {
            try {
                if (prefs.contains("transferError")) UpdateTransfer.clear(context)
                if (UpdateTransfer.id(context) < 0) UpdateTransfer.start(context, release)
                main.post {
                    visible?.let {
                        it.readTransfer()
                        it.askForNotifications()
                    }
                }
            } catch (e: Exception) {
                main.post { visible?.onState?.invoke(UpdateState(UpdatePhase.ERROR,
                    version = release.tag, message = "无法开始下载，${e.localizedMessage}")) }
            }
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || prefs.getBoolean("notificationAsked", false)) return
        prefs.edit().putBoolean("notificationAsked", true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4128)
    }

    private fun readTransfer() {
        if (visible !== this || reading) return
        reading = true
        main.removeCallbacks(poll)
        transfers.execute {
            val state = runCatching {
                UpdateTransfer.status(context) { verifying ->
                    main.post { if (visible === this) onState(verifying) }
                }
            }.getOrElse { UpdateState(UpdatePhase.ERROR, message = "无法读取下载状态，${it.localizedMessage}") }
            main.post {
                reading = false
                if (visible !== this) {
                    if (state?.phase == UpdatePhase.READY && visible == null) UpdateNotice.show(context)
                    return@post
                }
                if (state == null) {
                    UpdateNotice.cancel(context)
                    checkRelease(false)
                    return@post
                }
                onState(state)
                when (state.phase) {
                    UpdatePhase.DOWNLOADING -> main.postDelayed(poll, 750)
                    UpdatePhase.READY -> launchInstall(false)
                    else -> Unit
                }
            }
        }
    }

    fun install() = launchInstall(true)

    private fun launchInstall(manual: Boolean) {
        if (visible !== this || activity.isFinishing || activity.isDestroyed) return
        val id = UpdateTransfer.id(context)
        if (id < 0 || !UpdateTransfer.file(context, id).isFile) return
        val canInstall = activity.packageManager.canRequestPackageInstalls()
        val returningWithPermission = canInstall && prefs.getBoolean("permissionPending", false)
        if (!manual && prefs.getBoolean("installOpened", false) && !returningWithPermission) return
        // Mark before switching activities, so cancel/back and process recreation do not reopen the installer.
        prefs.edit().putBoolean("installOpened", true).putBoolean("permissionPending", !canInstall).commit()
        try {
            if (canInstall) {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(UpdateTransfer.uri(context, id), UpdateTransfer.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activity.startActivity(intent)
            } else {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")))
            }
            UpdateNotice.cancel(context)
        } catch (e: Exception) {
            onState(UpdateState(UpdatePhase.READY, version = UpdateTransfer.release(context)?.tag.orEmpty(),
                message = "无法打开系统安装界面，${e.localizedMessage}"))
        }
    }
}

class ReleaseCheckJob : JobService() {
    private var running: JobParameters? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = params
        // A cache hit can finish synchronously. Defer until onStartJob has returned.
        Handler(Looper.getMainLooper()).post {
            if (running === params) ReleaseFeed.check(applicationContext, false) {
                AppUpdater.visible?.showRelease()
                if (running === params) {
                    running = null
                    jobFinished(params, false)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        if (running === params) running = null
        return false
    }

    companion object {
        const val ID = 301028
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(ID) != null) return
            val job = JobInfo.Builder(ID, ComponentName(context, ReleaseCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(UPDATE_PERIOD).setPersisted(true).build()
            scheduler.schedule(job)
        }
    }
}

internal object UpdateNotice {
    private const val CHANNEL = "release_downloads"
    private const val ID = 301029
    fun cancel(context: Context) { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val action = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, ID, action,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID, Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("ASRtestII更新已下载").setContentText("点击继续安装")
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0 || id != UpdateTransfer.id(context)) return
        val pending = goAsync()
        Thread {
            try {
                if (runCatching { UpdateTransfer.downloaded(context, id) }.getOrDefault(false)) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (AppUpdater.visible == null) UpdateNotice.show(context)
                        } finally {
                            pending.finish()
                        }
                    }
                } else pending.finish()
            } catch (e: Exception) {
                pending.finish()
            }
        }.start()
    }
}
