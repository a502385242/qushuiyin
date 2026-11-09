package com.qushuiyin.app.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.qushuiyin.app.core.Http
import com.qushuiyin.app.model.Aweme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 进度回调：第一参数是给人看的文案，第二参数是 0~1 的比例（总长未知时为 null）。
 * Kotlin 的 typealias 只能写在文件顶层，不能嵌在 object 里。
 */
typealias DownloadProgress = (text: String, fraction: Float?) -> Unit

/**
 * 把解析结果落到相册。
 *
 * 三条约束决定了这里的写法：
 *
 * 1. **不转码**。CDN 给的是已经去掉水印的 mp4，原样写盘就是最高画质。
 *    任何「先解码再编码」的流程只会更差、还慢一个数量级。
 * 2. **Android 10 起没有存储权限这回事**。走 MediaStore + `IS_PENDING`，
 *    先占位再写入最后置 0，这样相册里不会出现半截文件；9 及以下才需要
 *    `WRITE_EXTERNAL_STORAGE`（清单里已声明 maxSdkVersion=28）。
 * 3. **失败要能说出原因**。用户报「存不进去」时，HTTP 码 / 磁盘满 / 权限拒
 *    这三种情况的处理方式完全不同，不能都变成一句「下载失败」。
 */
object Downloader {

    /** 相册里的子目录。用户一眼能认出这些是自己主动保存的 */
    const val ALBUM = "去水印"

    private const val BUF = 64 * 1024

    sealed interface Result {
        data class Ok(val count: Int, val where: String) : Result

        data class Fail(val message: String) : Result

        /** Android 9 及以下缺少存储权限，调用方去申请后重来 */
        data object NeedPermission : Result
    }

    suspend fun save(context: Context, aweme: Aweme, onProgress: DownloadProgress): Result =
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasLegacyPermission(context)) {
                    return@withContext Result.NeedPermission
                }
                val videoUrl = aweme.bestVideoUrl
                when {
                    aweme.isImagePost -> saveImages(context, aweme, onProgress)
                    videoUrl != null -> saveVideo(context, aweme, videoUrl, onProgress)
                    else -> Result.Fail("这条作品没有可保存的地址")
                }
            } catch (e: Exception) {
                // 这里的异常大多是流被中断（切网、CDN 掐连接），说清楚比抛栈有用
                Result.Fail("保存中断：" + (e.message?.take(60) ?: e.javaClass.simpleName))
            }
        }

    // ---------- 视频 ----------

    private fun saveVideo(context: Context, aweme: Aweme, url: String, onProgress: DownloadProgress): Result {
        val name = aweme.suggestedFileName("mp4")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveVideoViaMediaStore(context, url, name, onProgress)
        } else {
            saveVideoLegacy(context, url, name, onProgress)
        }
    }

    private fun saveVideoViaMediaStore(
        context: Context,
        url: String,
        name: String,
        onProgress: DownloadProgress
    ): Result {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: return Result.Fail("系统相册拒绝创建文件")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                streamTo(url, out, onProgress)
            } ?: run {
                resolver.delete(uri, null, null)
                return Result.Fail("打不开相册写入通道")
            }
            // 只有全部写完才置 0，否则相册里会出现一个播不了的半截文件
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return Result.Ok(1, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveVideoLegacy(context: Context, url: String, name: String, onProgress: DownloadProgress): Result {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            ALBUM
        )
        if (!dir.exists() && !dir.mkdirs()) return Result.Fail("无法创建目录 ${dir.absolutePath}")
        val target = uniqueFile(dir, name)
        FileOutputStream(target).use { out -> streamTo(url, out, onProgress) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
        return Result.Ok(1, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
    }

    // ---------- 图文 ----------

    private fun saveImages(context: Context, aweme: Aweme, onProgress: DownloadProgress): Result {
        val total = aweme.imageUrls.size
        var done = 0
        for ((index, url) in aweme.imageUrls.withIndex()) {
            val ext = guessImageExt(url)
            val name = aweme.suggestedFileName(ext)
                .let { if (total > 1) it.replace(".$ext", "_${index + 1}.$ext") else it }
            onProgress("正在保存第 ${index + 1}/$total 张", index.toFloat() / total)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveImageViaMediaStore(context, url, name, ext)
            } else {
                saveImageLegacy(context, url, name, ext)
            }
            done++
        }
        return if (done == 0) {
            Result.Fail("没有可保存的图片")
        } else {
            Result.Ok(done, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        }
    }

    private fun saveImageViaMediaStore(context: Context, url: String, name: String, ext: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeOf(ext))
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)?.use { out -> streamTo(url, out) { _, _ -> } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveImageLegacy(context: Context, url: String, name: String, ext: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM
        )
        if (!dir.exists() && !dir.mkdirs()) return
        val target = uniqueFile(dir, name)
        FileOutputStream(target).use { out -> streamTo(url, out) { _, _ -> } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeOf(ext)), null)
    }

    // ---------- 流式传输 ----------

    /**
     * 边读边写，不落临时文件。
     *
     * 大文件（实测 10 MB 起）先下到内存再写会增加一次拷贝，且 OOM 风险随片长上升；
     * 直接接到输出流是唯一稳的做法。进度按 200ms 节流，避免每 64KB 就刷一次 UI。
     */
    private fun streamTo(
        url: String,
        out: OutputStream,
        onProgress: DownloadProgress = { _, _ -> }
    ) {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .build()
        Http.downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("响应为空")
            val total = body.contentLength().takeIf { it > 0 }
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                val buf = ByteArray(BUF)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 200) {
                        lastReport = now
                        onProgress(progressText(done, total), fraction(done, total))
                    }
                }
            }
            out.flush()
            onProgress("已接收 " + humanSize(done), if (total != null && done >= total) 1f else null)
        }
    }

    private fun progressText(done: Long, total: Long?): String =
        if (total == null) "正在下载 " + humanSize(done)
        else "正在下载 " + humanSize(done) + " / " + humanSize(total)

    private fun fraction(done: Long, total: Long?): Float? =
        if (total == null || total <= 0) null else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)

    internal fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    }

    // ---------- 杂项 ----------

    private fun hasLegacyPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /** 同名文件加 (1)(2)，不覆盖用户已存的 */
    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "bin")
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base($i).$ext")
            i++
        }
        return f
    }

    internal fun guessImageExt(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "png"
            path.endsWith(".webp") -> "webp"
            path.endsWith(".gif") -> "gif"
            else -> "jpg"
        }
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"
}
