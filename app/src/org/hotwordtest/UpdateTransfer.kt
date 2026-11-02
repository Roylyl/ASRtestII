package org.hotwordtest

import android.app.DownloadManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

@Suppress("DEPRECATION")
internal fun Context.installedInfo(): PackageInfo = packageManager.getPackageInfo(packageName, 0)

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val version: String = "",
    val notes: String = "",
    val message: String = "",
    val bytes: Long = 0,
    val total: Long = -1,
    val offer: Boolean = false
)

internal object UpdateTransfer {
    const val MIME = "application/vnd.android.package-archive"
    fun id(context: Context) = context.updatePrefs().getLong("downloadId", -1)
    fun release(context: Context) = UpdateRelease.decode(context.updatePrefs().getString("downloadRelease", null))
    private fun manager(context: Context) = requireNotNull(context.getSystemService(DownloadManager::class.java))
    private fun folder(context: Context) = File(context.noBackupFilesDir, "release-apks")
    fun file(context: Context, id: Long) = File(folder(context), "$id.apk")
    fun uri(context: Context, id: Long) = Uri.Builder().scheme("content")
        .authority("${context.packageName}.updates").appendPath("$id.apk").build()

    fun start(context: Context, release: UpdateRelease) {
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle("ASRtestII ${release.tag}")
            .setDescription("正在下载更新")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                "ASRtestII-${release.tag}-${System.currentTimeMillis()}.apk")
        val downloadId = manager(context).enqueue(request)
        val recorded = context.updatePrefs().edit().putLong("downloadId", downloadId).putString("downloadRelease", release.json())
            .remove("transferError").remove("installOpened").remove("permissionPending").commit()
        if (!recorded) {
            manager(context).remove(downloadId)
            error("无法保存系统下载任务")
        }
    }

    fun clear(context: Context) {
        val id = id(context)
        if (id >= 0) {
            runCatching { manager(context).remove(id) }
            file(context, id).delete()
            File(folder(context), "$id.pending.apk").delete()
        }
        context.updatePrefs().edit().remove("downloadId").remove("downloadRelease")
            .remove("transferError").remove("installOpened").remove("permissionPending").apply()
    }

    fun status(context: Context, onVerifying: (UpdateState) -> Unit): UpdateState? {
        val id = id(context)
        val release = release(context) ?: return null
        val installed = ReleaseVersion.parse(context.installedInfo().versionName.orEmpty())
        val target = ReleaseVersion.tag(release.tag)
        if (installed != null && target != null && installed >= target) {
            clear(context)
            return null
        }
        val base = UpdateState(version = release.tag, notes = release.notes, total = release.size)
        val ready = base.copy(phase = UpdatePhase.READY, bytes = release.size, message = "更新包已就绪，可继续安装")
        if (file(context, id).isFile) return ready
        context.updatePrefs().getString("transferError", null)?.let {
            return base.copy(phase = UpdatePhase.ERROR, message = it)
        }
        return try {
            val row = manager(context).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                check(cursor != null && cursor.moveToFirst()) { "下载任务已被移除" }
                fun value(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
                val status = value(DownloadManager.COLUMN_STATUS).toInt()
                val bytes = value(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val total = value(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: release.size
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> null
                    DownloadManager.STATUS_FAILED -> error("系统下载失败（${value(DownloadManager.COLUMN_REASON)}）")
                    else -> base.copy(phase = UpdatePhase.DOWNLOADING, bytes = bytes, total = total,
                        message = when (status) {
                            DownloadManager.STATUS_PENDING -> "等待系统开始下载"
                            DownloadManager.STATUS_PAUSED -> "系统已暂停下载，将在条件允许时继续"
                            else -> "正在下载更新"
                        })
                }
            }
            if (row != null) return row
            onVerifying(base.copy(phase = UpdatePhase.VERIFYING, bytes = release.size, message = "正在校验安装包"))
            stage(context, id, release)
            runCatching { manager(context).remove(id) }
            ready
        } catch (e: Exception) {
            val message = "更新失败，${e.localizedMessage ?: "无法读取安装包"}"
            context.updatePrefs().edit().putString("transferError", message).apply()
            runCatching { manager(context).remove(id) }
            base.copy(phase = UpdatePhase.ERROR, message = message)
        }
    }

    fun downloaded(context: Context, id: Long): Boolean {
        if (id < 0 || id != this.id(context)) return false
        if (file(context, id).isFile) return true
        return manager(context).query(DownloadManager.Query().setFilterById(id)).use {
            it != null && it.moveToFirst() &&
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    private fun stage(context: Context, id: Long, release: UpdateRelease) {
        val dir = folder(context)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建安装包目录" }
        val part = File(dir, "$id.pending.apk")
        try {
            val descriptor = manager(context).openDownloadedFile(id)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                part.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            check(part.length() == release.size) { "下载字节数与发布附件不一致" }
            verify(context, part, release)
            check(part.renameTo(file(context, id))) { "无法保存校验过的安装包" }
        } finally {
            part.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(context: Context, file: File, release: UpdateRelease) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: error("无法解析APK")
        val local = pm.getPackageInfo(context.packageName, flags)
        check(apk.packageName == local.packageName) { "APK包名不匹配" }
        val version = ReleaseVersion.parse(apk.versionName.orEmpty()) ?: error("APK版本格式无效")
        val current = ReleaseVersion.parse(local.versionName.orEmpty()) ?: error("当前版本格式无效")
        check(version == ReleaseVersion.tag(release.tag) && version > current) { "APK版本与发布标签或当前版本不符" }
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        check(code(apk) >= code(local)) { "APK的versionCode低于当前版本" }
        fun signers(info: PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet().orEmpty()
        val currentSigners = signers(local)
        check(currentSigners.isNotEmpty() && signers(apk) == currentSigners) { "APK签名与已安装应用不一致" }
    }
}

// Only the private, verified copy is grantable. A stale URI cannot read a later transfer.
class UpdateApkProvider : ContentProvider() {
    override fun onCreate() = true
    private fun resolve(uri: Uri): File {
        val ctx = requireNotNull(context)
        val id = UpdateTransfer.id(ctx)
        if (id < 0 || uri != UpdateTransfer.uri(ctx, id) || !UpdateTransfer.file(ctx, id).isFile)
            throw FileNotFoundException("无有效的更新包")
        return UpdateTransfer.file(ctx, id)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("更新包只读")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = UpdateTransfer.MIME
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            val row = newRow()
            for (column in columns) row.add(when (column) {
                OpenableColumns.DISPLAY_NAME -> "ASRtestII.apk"
                OpenableColumns.SIZE -> file.length()
                else -> null
            })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
