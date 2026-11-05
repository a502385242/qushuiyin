package com.qushuiyin.app.parser

import com.qushuiyin.app.model.Aweme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 抖音作品解析器（纯本地，不依赖任何自建服务）。
 *
 * 链路：短链 → 跟随 302 拿作品 ID → 取详情 JSON → 按候选路径取作品对象
 *
 * 取详情有两条路，由 [ParseRules.sourceMode] 决定：
 *   - `webview`（默认）：离屏 WebView 让页面自己请求 `aweme/detail`，我们截获响应。
 *     该接口要 `a_bogus` 签名，纯客户端复刻等于长期逆向，所以交给页面自己算。
 *   - `http`：直接 GET 分享页、抠 `_ROUTER_DATA`。2026-11 起分享页只剩空壳，
 *     这条路的成功率已经是 0，保留只为了哪天线上的结构回滚时不用发版。
 *
 * 两条路的产物是同一种 JSON 结构，所以提取逻辑只有一份。
 */
class DouyinParser(
    private val http: OkHttpClient,
    rules: ParseRules = ParseRules(),
    /** 按当前规则造一个详情来源；为 null 时只能用 HTTP 路径 */
    private val detailSourceFactory: ((ParseRules) -> DetailSource)? = null
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

    suspend fun parse(target: LinkParser.Target, onStage: (String) -> Unit = {}): ParseResult =
        withContext(Dispatchers.IO) {
            onStage("正在获取作品 ID")
            val id = resolveId(target) ?: return@withContext ParseResult.Fail(ParseError.Network)
            val current = rules

            if (current.sourceMode == ParseRules.SOURCE_WEBVIEW && detailSourceFactory != null) {
                onStage("正在通过网页加载作品")
                val json = runCatching { detailSourceFactory.invoke(current).fetch(id) }.getOrNull()
                if (json != null) {
                    when (val r = parseDetailJson(json, id)) {
                        is ParseResult.Ok -> return@withContext r
                        // 详情拿到了但取不出地址 —— 这是结构问题，再去试已经死掉的 HTTP 路径没意义
                        is ParseResult.Fail ->
                            if (r.error == ParseError.Schema) return@withContext r
                    }
                }
                onStage("正在尝试备用通道")
            }

            when (val fetched = fetchSharePage(id)) {
                is Fetch.Fail -> ParseResult.Fail(fetched.error)
                is Fetch.Ok -> extractHtml(fetched.html, id)
            }
        }

    /** 短链 / 裸 ID 统一解析成作品 ID */
    suspend fun resolveId(target: LinkParser.Target): String? = withContext(Dispatchers.IO) {
        when (target) {
            is LinkParser.Target.AwemeId -> target.id
            is LinkParser.Target.ShortLink -> resolveShortLink(target.url)
        }
    }

    // ---------- 详情 JSON（WebView 截获） ----------

    /** 解析 `aweme/detail` 的响应体。与 HTML 路径共用同一套提取逻辑。 */
    fun parseDetailJson(jsonText: String, fallbackId: String): ParseResult {
        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
            ?: return ParseResult.Fail(ParseError.Schema)

        // 接口自己报错时不要算成「版式变了」，否则用户会得到一个点了没效果的修复按钮
        val code = (root as? JsonObject)?.get("status_code")?.let { (it as? JsonPrimitive)?.intOrNull }
        if (code != null && code != 0) {
            return ParseResult.Fail(ParseError.Unknown("接口返回 status_code=$code"))
        }
        return extractRoot(root, fallbackId)
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

    // ---------- 备用：抓分享页 ----------

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

    private fun extractHtml(html: String, fallbackId: String): ParseResult {
        val jsonText = HtmlExtractor.extractObject(html, "_ROUTER_DATA")
            ?: HtmlExtractor.extractRenderData(html)
            ?: return ParseResult.Fail(ParseError.Schema)

        val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
            ?: return ParseResult.Fail(ParseError.Schema)
        return extractRoot(root, fallbackId)
    }

    private fun extractRoot(root: JsonElement, fallbackId: String): ParseResult {
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

    private fun hasEmptyItemList(root: JsonElement): Boolean =
        rules.itemPaths.any { path ->
            val node = JsonPath.first(root, path.substringBeforeLast('.'))
            node is JsonArray && node.isEmpty()
        }

    /**
     * 视频地址：先按 bit_rate 从高到低排（这是「无损画质」的关键，
     * 直接取 play_addr 常常是中低码率档），再叠加配置里的兜底路径。
     *
     * 注意 `download_addr` 不要当成无水印来源——它带 `logo_name` 参数，是**加了水印**的那条。
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
