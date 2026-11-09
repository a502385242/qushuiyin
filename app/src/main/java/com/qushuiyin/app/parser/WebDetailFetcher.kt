package com.qushuiyin.app.parser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.qushuiyin.app.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 用一个离屏 WebView 让抖音自己的页面去取数据，我们只负责把结果捞出来。
 *
 * 为什么必须要 WebView：`aweme/detail` 接口要求 `a_bogus`、`uifid`、
 * `x-secsdk-web-signature` 三个动态参数，其中 `a_bogus` 是混淆 JS 算出来的，
 * 纯客户端复刻等于长期做逆向。让页面自己算，我们只读它算完之后请求回来的那份 JSON。
 *
 * 为什么必须是**桌面 UA**：移动 UA 下页面会跳去 `m.douyin.com/share/...`，
 * 那里只有一个 `playwm` + `logo_name` 的地址，也就是**带水印的 720p**。
 * 桌面版才会请求 `aweme/detail`，那里才有 12 档清晰度。
 *
 * 取数用**网络层转发**，不用 JS 钩子。这是 0.3.0 的修正：
 * 旧实现把拦截脚本放在 `onPageStarted` 里 `evaluateJavascript`，而那一刻新文档
 * 还没创建，脚本执行在**旧文档**的上下文里，页面一换就没了——钩子从来没生效过，
 * 表现出来就是「永远解析失败」。要在文档创建之前注入，只有
 * `WebViewCompat.addDocumentStartJavaScript`（需额外依赖、且要 WebView ≥ 105）或
 * `shouldInterceptRequest` 改写 HTML（要处理 gzip/br，容易踩坑）。
 *
 * 而 `shouldInterceptRequest` 还有个更省事的用法：**不注入任何东西，只旁观请求**。
 * 页面自己算出 `a_bogus` 发出 `/aweme/detail` 时，我们拿到完整 URL 和请求头，
 * 原样转发一次读回 JSON，再把同一份响应交还给 WebView——页面无感，我们拿数据。
 * 这条路不依赖任何注入时机，也不依赖 WebView 版本。
 */
class WebDetailFetcher(
    context: Context,
    private val urlTemplate: String = DEFAULT_URL_TEMPLATE,
    private val userAgent: String = DEFAULT_DESKTOP_UA,
    /** 详情接口的 URL 片段，命中即转发。来自解析规则，改版时能靠热更新救回来 */
    private val apiPaths: List<String> = DEFAULT_API_PATHS,
    private val http: OkHttpClient = Http.client
) : DetailSource {

    private val appContext = context.applicationContext

    /** 转发用的客户端：加一层整体超时，避免页面等我们等到用户以为卡死 */
    private val forwardClient: OkHttpClient by lazy {
        http.newBuilder()
            .callTimeout(FORWARD_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun fetch(awemeId: String): String? = withTimeoutOrNull(TIMEOUT_MS) {
        withContext(Dispatchers.Main) { load(awemeId) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(awemeId: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(appContext)
        val settled = AtomicBoolean(false)

        fun settle(result: String?) {
            if (!settled.compareAndSet(false, true)) return
            // 不能立刻 destroy：此刻 WebView 可能正拿着我们刚返回的响应流在读，
            // 直接销毁会让它读到一个已关闭的流。给半秒缓冲。
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
            }, DESTROY_DELAY_MS)
            if (cont.isActive) cont.resume(result)
        }

        fun inject(view: WebView?) {
            if (settled.get()) return
            view?.evaluateJavascript(HOOK_JS, null)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 图片照常加载：我们要的是「看起来就是个普通浏览器」。
            // 关掉图片确实能省几百 KB，但省这点流量的收益远小于被判定成异常客户端的风险。
            blockNetworkImage = false
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgent
            // 页面里有视频，不主动播，省流量也避免抢占 decoder
            mediaPlaybackRequiresUserGesture = true
        }

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onDetail(json: String) {
                    if (json.isNotBlank()) settle(json)
                }
            },
            BRIDGE_NAME
        )

        webView.webViewClient = object : WebViewClient() {
            /**
             * 主入口：页面发 `/aweme/detail` 时截住它，用页面自己算好的 URL 与请求头
             * 转发一次，把响应体同时交给上层和 WebView。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null || request.method != "GET") return null
                val url = request.url?.toString() ?: return null
                if (!matchesDetailApi(url)) return null

                val body = forward(url, request.requestHeaders) ?: return null
                if (body.contains(DETAIL_KEY)) settle(body)
                return WebResourceResponse(
                    "application/json",
                    "utf-8",
                    ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                )
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) = inject(view)

            override fun onPageFinished(view: WebView?, url: String?) {
                inject(view)
                // 兜底：详情接口没被截到（页面改走别的通道、或我们转发失败）时，
                // 退一步读页面里 <video> 的地址。只有一档清晰度，但总比失败强。
                if (view != null) {
                    view.postDelayed({ readVideoTag(view) { settle(it) } }, TAG_FALLBACK_DELAY_MS)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) settle(null)
            }
        }

        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post { runCatching { webView.destroy() } }
        }

        webView.loadUrl(urlTemplate.replace("{id}", awemeId))
    }

    // ---------- 转发 ----------

    private fun matchesDetailApi(url: String): Boolean =
        DetailApiMatcher.matches(url, apiPaths)

    /**
     * 用页面自己的 URL + 请求头再发一次，读回响应体。
     *
     * 关键点：
     *  - **不能带 `Accept-Encoding`**：交给 OkHttp 自己协商，它会顺带把 gzip 解掉，
     *    我们拿到的是明文，返回给 WebView 时也就不用再声明编码。
     *  - `Cookie` 优先取请求头里的（和 WebView 当期状态一致），没有再问 CookieManager。
     *  - 上游非 2xx 或空体时返回 null，把这次请求还给 WebView 自己发，
     *    免得我们的转发结果把页面带崩。
     */
    private fun forward(url: String, headers: Map<String, String>): String? {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", header("Accept") ?: "application/json, text/plain, */*")
            .header("User-Agent", header("User-Agent") ?: userAgent)

        header("Referer")?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        (header("Cookie") ?: CookieManager.getInstance().getCookie(url))
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.header("Cookie", it) }

        // 抖音会看 uifid / sec-ch-ua / x-secsdk-web-signature 这些头，能带就带
        headers.forEach { (k, v) ->
            val key = k.lowercase()
            if (key !in SKIPPED_HEADERS && v.isNotBlank()) {
                runCatching { builder.header(k, v) }
            }
        }

        return try {
            forwardClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读 `<video src>` 作为最后手段。包成与详情接口同构的 JSON，
     * 这样上层不用为它写第二套解析逻辑。
     */
    private fun readVideoTag(view: WebView, deliver: (String) -> Unit) {
        view.evaluateJavascript(
            "(() => { const v = document.querySelector('video');" +
                " return v ? (v.currentSrc || v.src || '') : ''; })()"
        ) { raw ->
            val url = raw?.trim()?.trim('"').orEmpty()
            if (!url.startsWith("http")) return@evaluateJavascript
            val json = JSONObject()
                .put(
                    "aweme_detail",
                    JSONObject().put(
                        "video",
                        JSONObject().put(
                            "play_addr",
                            JSONObject().put("url_list", JSONArray().put(url))
                        )
                    )
                )
                .toString()
            deliver(json)
        }
    }

    companion object {
        private const val BRIDGE_NAME = "QuShuiYin"
        private const val DETAIL_KEY = "aweme_detail"
        private const val TIMEOUT_MS = 30_000L
        private const val FORWARD_TIMEOUT_SEC = 12L
        private const val TAG_FALLBACK_DELAY_MS = 8_000L
        private const val DESTROY_DELAY_MS = 500L

        const val DEFAULT_URL_TEMPLATE = "https://www.douyin.com/video/{id}"

        /**
         * 详情接口的候选片段。第一个是当前线上路径；第二个是去掉版本前缀的宽松匹配，
         * 用来扛「接口换版本号」这种小改版。
         */
        val DEFAULT_API_PATHS = listOf(
            "/aweme/v1/web/aweme/detail/",
            "/aweme/detail/"
        )

        /** OkHttp 不允许手工设置的头，以及会让响应体被二次编码的头 */
        private val SKIPPED_HEADERS = setOf(
            "host", "connection", "content-length", "accept-encoding",
            "transfer-encoding", "upgrade", "cookie", "user-agent", "accept"
        )

        /**
         * 桌面 UA。刻意不跟随系统：移动 UA 拿到的是带水印的 720p。
         * 这是「无损画质」能不能成立的前提，不要顺手改成 WebSettings.getDefaultUserAgent()。
         */
        const val DEFAULT_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 辅助钩子：网络层已经是主路径，这里只是第二保险——
         * 万一某些版本的 WebView 不走 shouldInterceptRequest，页面自己发出的 XHR
         * 也能被这里兜住。XHR 和 fetch 都挂，是因为页面用哪种取决于它内部封装。
         */
        private val HOOK_JS = """
            (function () {
              if (window.__qsyHooked) return;
              window.__qsyHooked = true;
              var isDetail = function (u) { return String(u || '').indexOf('/aweme/detail') >= 0; };
              var post = function (t) {
                try { if (t && t.indexOf('aweme_detail') >= 0) window.$BRIDGE_NAME.onDetail(t); } catch (e) {}
              };
              var open = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function (m, u) { this.__qsyUrl = u; return open.apply(this, arguments); };
              var send = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function () {
                var self = this;
                self.addEventListener('load', function () {
                  try { if (isDetail(self.__qsyUrl)) post(self.responseText); } catch (e) {}
                });
                return send.apply(this, arguments);
              };
              if (window.fetch) {
                var f = window.fetch;
                window.fetch = function () {
                  return f.apply(this, arguments).then(function (r) {
                    try {
                      if (isDetail(r && r.url)) r.clone().text().then(post, function () {});
                    } catch (e) {}
                    return r;
                  });
                };
              }
            })();
        """.trimIndent()
    }
}
