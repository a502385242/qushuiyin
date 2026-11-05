package com.qushuiyin.app.parser

import com.qushuiyin.app.model.Aweme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 抖音作品解析器（纯本地，不依赖任何自建服务）。
 *
 * 链路：短链 → 跟随 302 拿作品 ID → 请求分享页 HTML → 抠 _ROUTER_DATA → 按候选路径取作品对象
 *
 * 刻意不碰 aweme/detail 接口：那需要 a_bogus 签名，纯客户端实现等于持续做逆向。
 * 分享页是普通 GET，改版频率低得多。
 */
class DouyinParser(
    private val http: OkHttpClient,
    rules: ParseRules = ParseRules()
) {

    sealed interface ParseResult {
        data class Ok(val aweme: Aweme) : ParseResult
        data class Fail(val error: ParseError) : ParseResult
    }

    private sealed interface Fetch {
        data class Ok(val html: String) : Fetch
        data class Fail(val error: ParseError) : Fetch
    }

    /** 修复成功后可热替换规则，无需重建实例 */
    @Volatile
    private var rules: ParseRules = rules

    /** 当前生效的规则。修复时要把它的版本号带过去，才知道远端是不是真有更新 */
    val currentRules: ParseRules get() = rules

    fun updateRules(newRules: ParseRules) {
        rules = newRules
    }

    suspend fun parse(target: LinkParser.Target): ParseResult = withContext(Dispatchers.IO) {
        val id = when (target) {
            is LinkParser.Target.AwemeId -> target.id
            is LinkParser.Target.ShortLink ->
                resolveShortLink(target.url) ?: return@withContext ParseResult.Fail(ParseError.Network)
        }

        when (val fetched = fetchSharePage(id)) {
            is Fetch.Fail -> ParseResult.Fail(fetched.error)
            is Fetch.Ok -> extract(fetched.html, id)
        }
    }

    // ---------- 短链解析 ----------

    private fun resolveShortLink(shortUrl: String): String? {
        val req = Request.Builder()
            .url(shortUrl)
            .headers(defaultHeaders())
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                // OkHttp 会自动跟随 302，这里拿到的是最终地址
                val finalUrl = resp.request.url.toString()
                when (val t = LinkParser.parse(finalUrl)) {
                    is LinkParser.Target.AwemeId -> t.id
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    // ---------- 抓分享页 ----------

    private fun fetchSharePage(id: String): Fetch {
        var lastError: ParseError = ParseError.Network

        for (host in rules.shareHosts) {
            val url = "https://$host/share/video/$id/"
            val req = Request.Builder().url(url).headers(defaultHeaders()).get().build()
            try {
                http.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> return Fetch.Fail(ParseError.Gone)
                        resp.code == 403 || resp.code == 401 -> lastError = ParseError.Blocked
                        !resp.isSuccessful -> lastError = ParseError.Unknown("HTTP ${resp.code}")
                        else -> {
                            val body = resp.body?.string().orEmpty()
                            if (!isVerificationPage(body)) return Fetch.Ok(body)
                            lastError = ParseError.Blocked
                        }
                    }
                }
            } catch (e: IOException) {
                lastError = ParseError.Network
            }
        }
        return Fetch.Fail(lastError)
    }

    /** 抖音被风控时会返回 200 + 一个滑块验证页，这种情况不能当成「版式变了」 */
    private fun isVerificationPage(html: String): Boolean =
        html.length < 4096 && (
            html.contains("captcha", ignoreCase = true) ||
                html.contains("verify") && html.contains("滑块")
            )

    // ---------- 数据提取 ----------

    private fun extract(html: String, fallbackId: String): ParseResult {
        val jsonText = HtmlExtractor.extractObject(html, "_ROUTER_DATA")
            ?: HtmlExtractor.extractRenderData(html)
            ?: return ParseResult.Fail(ParseError.Schema)

        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
            ?: return ParseResult.Fail(ParseError.Schema)

        val item = rules.itemPaths.firstNotNullOfOrNull { path ->
            JsonPath.first(root, path) as? JsonObject
        } ?: return ParseResult.Fail(
            // 路径本身能走通、只是列表为空 —— 作品被删或转私密，不是版式问题
            if (hasEmptyItemList(root)) ParseError.Gone else ParseError.Schema
        )

        val videoUrls = extractVideoUrls(item)
        val imageUrls = extractImageUrls(item)
        if (videoUrls.isEmpty() && imageUrls.isEmpty()) {
            return ParseResult.Fail(ParseError.Schema)
        }

        val aweme = Aweme(
            id = JsonPath.firstString(item, "aweme_id") ?: fallbackId,
            desc = JsonPath.firstString(item, "desc").orEmpty(),
            authorName = JsonPath.firstString(item, "author.nickname").orEmpty(),
            coverUrl = JsonPath.firstString(item, "video.cover.url_list[0]")
                ?: JsonPath.firstString(item, "video.origin_cover.url_list[0]"),
            videoUrls = videoUrls,
            imageUrls = imageUrls
        )
        return ParseResult.Ok(aweme)
    }

    private fun hasEmptyItemList(root: kotlinx.serialization.json.JsonElement): Boolean =
        rules.itemPaths.any { path ->
            val listPath = path.substringBeforeLast('.')
            val node = JsonPath.first(root, listPath)
            node is JsonArray && node.isEmpty()
        }

    /**
     * 视频地址：先按 bit_rate 从高到低排（这是「无损画质」的关键，
     * 直接取 play_addr 常常是中低码率档），再叠加配置里的兜底路径。
     */
    private fun extractVideoUrls(item: JsonObject): List<String> {
        val byBitrate = JsonPath.query(item, "video.bit_rate[]")
            .mapNotNull { it as? JsonObject }
            .mapNotNull { br ->
                val rate = (br["bit_rate"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                val url = JsonPath.firstString(br, "play_addr.url_list[0]") ?: return@mapNotNull null
                rate to url
            }
            .sortedByDescending { it.first }
            .map { cleanVideoUrl(it.second) }

        val byPath = rules.videoUrlPaths
            .flatMap { p -> JsonPath.query(item, p) }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map(::cleanVideoUrl)

        return (byBitrate + byPath).filter { it.isNotBlank() }.distinct()
    }

    private fun extractImageUrls(item: JsonObject): List<String> =
        rules.imageUrlPaths
            .flatMap { p -> JsonPath.query(item, p) }
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map(::stripImageProcessor)
            .filter { it.isNotBlank() }
            .distinct()

    private fun defaultHeaders(): Headers = Headers.Builder()
        .add("User-Agent", rules.ua)
        .add("Referer", "https://www.douyin.com/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9")
        .build()

    companion object {
        /** 老的取巧办法：把带水印路径换成无水印路径。仍然有效，作为兜底 */
        internal fun cleanVideoUrl(url: String): String =
            url.replace("/playwm/", "/play/").replace("playwm", "play")

        /**
         * 图片 URL 上常挂着 `~tplv-dy-aweme-images:q75.webp` 这类处理参数，
         * 去掉 `~` 到 `?` 之间那段才是原图，否则存下来是压缩过的缩略图。
         */
        internal fun stripImageProcessor(url: String): String {
            val tilde = url.indexOf('~')
            if (tilde <= 0) return url
            val q = url.indexOf('?', tilde)
            return url.substring(0, tilde) + if (q >= 0) url.substring(q) else ""
        }
    }
}
