package com.qushuiyin.app.repair

import com.qushuiyin.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从多个镜像并发拉取规则文件，取**最先成功**的一份。
 *
 * 为什么不是「按顺序试」：国内访问 raw.githubusercontent.com 时常卡在 DNS 阶段，
 * 顺序重试的话第一个镜像超时 4 秒才轮到第二个，用户会以为按钮没反应。
 * 并发发三个请求，谁先回来用谁，总耗时等于最快那个。
 *
 * 为什么 manifest 和 .sig 要成对拉：签名是针对 manifest 原始字节的，
 * 两份必须来自同一个镜像、同一次发布，混着用会验不过（也可能验过一个错误的组合）。
 */
class MirrorClient(
    private val http: OkHttpClient,
    private val mirrors: List<String> = parseMirrors(BuildConfig.REPAIR_MIRRORS)
) {

    class Bundle(val raw: ByteArray, val signature: String, val mirror: String)

    suspend fun fetch(): Bundle? = coroutineScope {
        if (mirrors.isEmpty()) return@coroutineScope null

        val first = Channel<Bundle>(Channel.CONFLATED)
        val jobs = mirrors.map { base ->
            launch(Dispatchers.IO) {
                runCatching { fetchFrom(base) }.getOrNull()?.let { first.send(it) }
            }
        }
        // 三个镜像都失败时立刻收摊，不要干等到总超时
        launch {
            jobs.joinAll()
            first.close()
        }

        val result = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            first.receiveCatching().getOrNull()
        }
        jobs.forEach { it.cancel() }
        result
    }

    private suspend fun fetchFrom(base: String): Bundle? = coroutineScope {
        val manifestJob = async(Dispatchers.IO) { get(base + MANIFEST_FILE) }
        val sigJob = async(Dispatchers.IO) { get(base + SIG_FILE) }
        val raw = manifestJob.await() ?: return@coroutineScope null
        val sig = sigJob.await() ?: return@coroutineScope null
        if (raw.isEmpty() || sig.isEmpty()) return@coroutineScope null
        Bundle(raw, String(sig, Charsets.UTF_8).trim(), base)
    }

    private suspend fun get(url: String): ByteArray? = withTimeoutOrNull(PER_REQUEST_TIMEOUT_MS) {
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "QuShuiYin/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val len = resp.body?.contentLength() ?: -1L
                if (len > MAX_BYTES) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                if (bytes.size > MAX_BYTES) null else bytes
            }
        }.getOrNull()
    }

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
        private const val SIG_FILE = "manifest.json.sig"

        /** 单个请求超时 */
        private const val PER_REQUEST_TIMEOUT_MS = 6_000L

        /** 整轮竞速的总预算：再慢就告诉用户「没拉到」，不要让他等 */
        private const val TOTAL_TIMEOUT_MS = 5_000L

        /** 规则文件正常只有 1~4KB，超过这个量级说明拉到了错误的东西（比如一个 HTML 错误页） */
        private const val MAX_BYTES = 256 * 1024

        fun parseMirrors(raw: String): List<String> =
            raw.split(',', '\n')
                .map { it.trim() }
                .filter { it.startsWith("http") }
                .map { if (it.endsWith("/")) it else "$it/" }
                .distinct()
    }
}
