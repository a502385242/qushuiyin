package com.qushuiyin.app.repair

import com.qushuiyin.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从多个镜像并发拉取规则文件，把**窗口内所有成功的响应**都收上来交给调用方挑选。
 *
 * 为什么不是「谁先回来用谁」：jsDelivr 对 `@main` 有约 12 小时缓存，而且不认 query 参数，
 * 于是它常常比 raw.githubusercontent.com 先回但内容更旧。竞速取第一个的结果就是
 * 旧规则永远赢——推了 v3，用户点修复还是拿 v2。所以这里只负责「收」，选哪份由
 * [RulesGate.choose] 按版本号决定。
 *
 * 为什么 manifest 和 .sig 要成对拉：签名是针对 manifest 原始字节的，
 * 两份必须来自同一个镜像、同一次发布，混着用会验不过。
 */
class MirrorClient(
    private val http: OkHttpClient,
    private val mirrors: List<String> = parseMirrors(BuildConfig.REPAIR_MIRRORS)
) {

    class Bundle(val raw: ByteArray, val signature: String, val mirror: String)

    /** 收齐所有镜像的响应（失败的镜像贡献 null），最多等 [TOTAL_WINDOW_MS]；全部返回则立即结束 */
    suspend fun fetchAll(): List<Bundle> = coroutineScope {
        if (mirrors.isEmpty()) return@coroutineScope emptyList()

        val channel = Channel<Bundle?>(Channel.UNLIMITED)
        val jobs = mirrors.map { base ->
            launch(Dispatchers.IO) {
                channel.send(runCatching { fetchFrom(base) }.getOrNull())
            }
        }

        val collected = mutableListOf<Bundle>()
        withTimeoutOrNull(TOTAL_WINDOW_MS) {
            var received = 0
            while (received < mirrors.size) {
                val bundle = channel.receive()
                received++
                if (bundle != null) collected.add(bundle)
            }
        }
        jobs.forEach { it.cancel() }
        collected
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
        private const val PER_REQUEST_TIMEOUT_MS = 4_000L

        /** 收款窗口：再慢就当作没拉到。三个镜像都返回时不必等满 */
        private const val TOTAL_WINDOW_MS = 5_000L

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
