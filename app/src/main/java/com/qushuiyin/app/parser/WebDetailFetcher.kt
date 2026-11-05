package com.qushuiyin.app.parser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 用一个离屏 WebView 让抖音自己的页面去取数据，我们只负责截获结果。
 *
 * 为什么必须要 WebView：`aweme/detail` 接口要求 `a_bogus`、`uifid`、`x-secsdk-web-signature`
 * 三个动态参数，其中 `a_bogus` 是混淆 JS 算出来的，纯客户端复刻等于长期做逆向。
 * 让页面自己算，我们只读它算完之后请求回来的那份 JSON——改版时跟着改的是他们，不是我们。
 *
 * 为什么必须是**桌面 UA**：移动 UA 下页面会跳去 `m.douyin.com/share/...`，
 * 那里只有一个 `playwm` + `logo_name` 的地址，也就是**带水印的 720p**。
 * 桌面版才会请求 `aweme/detail`，那里才有 12 档清晰度。
 *
 * 代价：比一个 GET 慢 3~8 秒，还要跑一个 WebView 实例。这是目前纯本地方案
 * 能拿到无水印高清流的唯一路径，值得。
 */
class WebDetailFetcher(
    context: Context,
    private val urlTemplate: String = DEFAULT_URL_TEMPLATE,
    private val userAgent: String = DEFAULT_DESKTOP_UA
) : DetailSource {

    private val appContext = context.applicationContext

    override suspend fun fetch(awemeId: String): String? = withTimeoutOrNull(TIMEOUT_MS) {
        withContext(Dispatchers.Main) { load(awemeId) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(awemeId: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(appContext)
        var settled = false

        fun settle(result: String?) {
            if (settled) return
            settled = true
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
            }
            if (cont.isActive) cont.resume(result)
        }

        fun inject(view: WebView?) {
            if (settled) return
            view?.evaluateJavascript(HOOK_JS, null)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 不加载图片：只要 JSON，画面一张都不用画，省流量也更快出结果
            blockNetworkImage = true
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgent
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = inject(view)

            override fun onPageFinished(view: WebView?, url: String?) {
                inject(view)
                // 兜底：万一详情接口没被截到（比如页面改走别的通道），
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
        private const val TIMEOUT_MS = 30_000L
        private const val TAG_FALLBACK_DELAY_MS = 6_000L

        const val DEFAULT_URL_TEMPLATE = "https://www.douyin.com/video/{id}"

        /**
         * 桌面 UA。刻意不跟随系统：移动 UA 拿到的是带水印的 720p。
         * 这是「无损画质」能不能成立的前提，不要顺手改成 WebSettings.getDefaultUserAgent()。
         */
        const val DEFAULT_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 在页面脚本执行前挂上钩子，截获 `/aweme/detail` 的响应体。
         * XHR 和 fetch 都挂，是因为页面用哪种取决于它内部封装，改版时会换。
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
