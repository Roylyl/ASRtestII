package org.hotwordtest

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/** Presentation only. The system download and install state outlives this activity. */
internal class UpdateUi(private val host: Activity) {
    private val blue = Color.rgb(0, 122, 255)
    private var last = UpdateState()
    private val updater = AppUpdater(host, ::changed)
    private var hint: TextView? = null
    private var action: TextView? = null
    private var offer: AlertDialog? = null
    private var progress: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    fun resume() = updater.resume()
    fun pause() = updater.pause()
    fun close() { offer?.dismiss(); progress?.dismiss() }

    private fun dp(value: Int) = (host.resources.displayMetrics.density * value + .5f).toInt()
    private fun label(size: Int, color: Int) = TextView(host).apply {
        textSize = size.toFloat()
        setTextColor(color)
    }
    private fun size(bytes: Long) = when {
        bytes < 1_000 -> "${bytes}B"
        bytes < 1_000_000 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1_000.0)
        else -> String.format(Locale.getDefault(), "%.1fMB", bytes / 1_000_000.0)
    }

    fun addTo(group: LinearLayout) {
        val version = host.packageManager.getPackageInfo(host.packageName, 0).versionName.orEmpty()
        group.addView(label(15, Color.BLACK).apply {
            text = "当前版本V$version"
            setPadding(0, dp(8), 0, dp(4))
        })
        hint = label(13, Color.DKGRAY).apply { setPadding(0, 0, 0, dp(10)); group.addView(this) }
        action = label(16, blue).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.rgb(235, 244, 255))
                cornerRadius = dp(12).toFloat()
            }
            onHapticClick {
                when (last.phase) {
                    UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> showProgress()
                    UpdatePhase.READY -> updater.install()
                    else -> updater.check()
                }
            }
            group.addView(this)
        }
        refreshCard()
    }

    private fun refreshCard() {
        hint?.text = last.message.ifBlank { "启动时自动检查，之后每30分钟检查一次。" }
        action?.apply {
            text = when (last.phase) {
                UpdatePhase.CHECKING -> "正在检查更新…"
                UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> "查看下载进度"
                UpdatePhase.READY -> "继续安装更新"
                else -> "检查更新"
            }
            isEnabled = last.phase != UpdatePhase.CHECKING
            alpha = if (isEnabled) 1f else .55f
        }
    }

    private fun changed(state: UpdateState) {
        if (host.isFinishing || host.isDestroyed) return
        last = state
        refreshCard()
        when (state.phase) {
            UpdatePhase.AVAILABLE -> if (state.offer && offer?.isShowing != true) showOffer(state)
            UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> refreshProgress()
            UpdatePhase.ERROR -> if (progress?.isShowing == true) {
                progress?.dismiss()
                host.hapticDialog().setTitle("更新未完成").setMessage(state.message)
                    .setPositiveButton("知道了") { d, _ -> host.dialogTap(d) }.show()
            }
            else -> progress?.dismiss()
        }
    }

    private fun showOffer(state: UpdateState) {
        val details = buildString {
            if (state.notes.isNotBlank()) append(state.notes.trim()).append("\n\n")
            if (state.total > 0) append("安装包大小约").append(size(state.total)).append("。")
            append("可以在后台下载，完成后由系统确认安装。")
        }
        offer = host.hapticDialog().setTitle("发现新版本${state.version}")
            .setMessage(details)
            .setNegativeButton("稍后") { d, _ -> host.dialogTap(d) }
            .setPositiveButton("下载更新") { d, _ ->
                host.dialogTap(d)
                showProgress()
                updater.download(state.version)
            }.show()
    }

    private fun showProgress() {
        if (progress?.isShowing == true) return
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        progressBar = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(blue)
            indeterminateTintList = ColorStateList.valueOf(blue)
            box.addView(this, LinearLayout.LayoutParams(-1, dp(12)))
        }
        progressText = label(14, Color.DKGRAY).apply { setPadding(0, dp(10), 0, 0); box.addView(this) }
        refreshProgress()
        progress = host.hapticDialog().setTitle("下载更新${last.version}").setView(box)
            .setPositiveButton("后台下载") { d, _ -> host.dialogTap(d) }.show()
    }

    private fun refreshProgress() {
        val downloading = last.phase == UpdatePhase.DOWNLOADING
        val known = downloading && last.total > 0
        val percent = if (known) (100.0 * last.bytes / last.total).toInt().coerceIn(0, 100) else 0
        progressBar?.isIndeterminate = !known
        if (known) progressBar?.progress = percent
        progressText?.text = when {
            known -> "${last.message} · $percent%\n${size(last.bytes)}/${size(last.total)}"
            downloading -> "${last.message} · 已下载${size(last.bytes)}"
            last.phase == UpdatePhase.VERIFYING -> last.message
            else -> "正在准备下载…"
        }
    }
}
