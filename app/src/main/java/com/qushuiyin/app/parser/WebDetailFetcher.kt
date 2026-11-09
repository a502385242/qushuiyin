package com.qushuiyin.app.parser

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.qushuiyin.app.App
import com.qushuiyin.app.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Collections
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
 * 还没创建，脚本执行在**旧文档**的上下文里，页面一换就没了——钩子从来没生效过。
 *
 * 0.3.1 起每条链路都往 [trace] 里记一笔。真机上到底卡在哪一步（页面没打开 / 页面
 * 没发请求 / 请求发了但转发失败 / 响应里没有作品对象），只有这一份记录能说清；
 * 用户手机上没有 adb，UI 上的「诊断信息」就是唯一的取证手段。
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

    private val sb = StringBuilder()

    /** 已经处理过的详情 URL。页面内 fetch 自己也会再走一遍 shouldInterceptRequest，不去重会打转 */
    private val fired: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 给 UI 看的取证记录。多线程写入（网络线程 / 主线程 / JS 回调），必须加锁 */
    val trace: String get() = synchronized(sb) { sb.toString() }

    private fun note(msg: String) = synchronized(sb) {
        if (sb.length < TRACE_MAX) sb.append(msg).append('\n')
    }

    override suspend fun fetch(awemeId: String): String? {
        note("ua: " + systemWebViewVersion())
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { load(awemeId) }
        } ?: run {
            note("done: 超时 ${TIMEOUT_MS / 1000}s，没等到详情")
            null
        }
    }

    /** 系统 WebView 内核版本。抖音页面在太旧的内核上会直接降级，这是最常见的真机差异 */
    private fun systemWebViewVersion(): String = runCatching {
        val p = WebView.getCurrentWebViewPackage()
        if (p == null) "无法获取" else "${p.packageName} ${p.versionName}"
    }.getOrDefault("无法获取")

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(awemeId: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(appContext)
        val settled = AtomicBoolean(false)
        val host = attachToWindow(webView)

        fun settle(result: String?, by: String) {
            if (!settled.compareAndSet(false, true)) return
            note("done: $by -> " + if (result == null) "空" else "${result.length} 字节")
            // 不能立刻销毁：此刻 WebView 可能正拿着我们刚返回的响应流在读，
            // 直接销毁会让它读到一个已关闭的流。给半秒缓冲。
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    host?.dismiss()
                    webView.destroy()
                }
            }, DESTROY_DELAY_MS)
            if (cont.isActive) cont.resume(result)
        }

        fun inject(view: WebView?) {
            if (settled.get()) return
            view?.evaluateJavascript(HOOK_JS, null)
        }

        /**
         * 自己用 OkHttp 转发。
         *
         * 成败取决于能不能带上 cookie——真机上 `CookieManager.getCookie()` 在
         * shouldInterceptRequest 的后台线程里常返回空，服务端于是回 200 空体。
         * 所以这个函数要求调用方**在主线程先取好 cookie** 再传进来。
         */
        fun forwardFallback(url: String, cookie: String?) {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://www.douyin.com/")
                .apply { cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) } }
                .build()
            runCatching {
                forwardClient.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        note("转发异常: ${e.javaClass.simpleName} ${e.message?.take(60)}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = it.body?.string()
                            note("转发 HTTP ${it.code}, ${body?.length ?: 0} 字节")
                            if (!body.isNullOrBlank() && body.contains(DETAIL_KEY)) {
                                settle(body, "OkHttp 转发")
                            }
                        }
                    }
                })
            }.onFailure { note("转发发起失败: ${it.javaClass.simpleName}") }
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
            mediaPlaybackRequiresUserGesture = true
        }

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onDetail(json: String) {
                    if (json.isNotBlank()) settle(json, "JS 钩子")
                }
            },
            BRIDGE_NAME
        )

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                note("开始加载: ${url?.take(120)}")
            }

            /**
             * 命中详情接口时**不接管**这次请求，只借它的 URL 在页面里重新请求一次。
             *
             * 为什么不能自己用 OkHttp 转发：真机诊断实测，转发拿到的是 `HTTP 200 + 0 字节`，
             * 而同一台手机上用浏览器打开一切正常。对照实验（tools/probe-ua.js）证明
             * 唯一会触发空体的是**没有 Cookie**——UA 与 sec-ch-ua 版本不一致都不影响。
             * `CookieManager.getCookie()` 在 shouldInterceptRequest 的后台线程里经常拿到空值，
             * 而 WebView 网络栈自己发请求时会自动带上 cookie。
             *
             * 所以改成在**页面上下文**里 fetch 同一个 URL：同源、凭据自动携带、
             * Referer 天然正确，服务端就当它是页面自己发的。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null || request.method != "GET") return null
                val url = request.url?.toString() ?: return null
                if (!matchesDetailApi(url)) return null

                note("命中详情接口: ${url.take(110)}")
                // 同一个 URL 只处理一次：我们自己发的请求也会再走一遍这里，不去重会打转
                if (fired.add(url) && view != null) {
                    view.post {
                        if (settled.get()) return@post
                        /*
                         * 两条路同时走，谁先拿到带 aweme_detail 的响应就用谁。
                         *
                         * 为什么要两条：真机实测两条路各有一个坑——
                         *  - OkHttp 自己发：后台线程里 CookieManager 常返回空，服务端回 200 空体；
                         *  - 页面内 fetch：同源同 cookie，但可能被页面的 Service Worker 拦下，
                         *    返回的是 SPA 的 HTML 外壳（实测 84 万字节，不含任何作品数据）。
                         * 单押任何一条都可能失败，两条并行才稳。
                         */
                        val cookie = runCatching {
                            CookieManager.getInstance().getCookie(url)
                        }.getOrNull()
                        note("主线程读 Cookie 长度: ${cookie?.length ?: 0}")
                        forwardFallback(url, cookie)
                        if (!settled.get()) {
                            note("页面内 fetch 同一 URL")
                            view.evaluateJavascript(jsFetchDetail(url), null)
                        }
                    }
                }
                // 返回 null = 不拦截，页面自己那次请求照常进行，页面不会因为我们而变空
                return null
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) = inject(view)

            override fun onPageFinished(view: WebView?, url: String?) {
                note("加载完成: ${url?.take(120)}")
                inject(view)
                if (view != null) {
                    // 记录页面真实状态：标题为空 / 是验证页 / HTML 极小 / 可见性 hidden，
                    // 这几种病因的处理方式完全不同，不能靠猜
                    view.evaluateJavascript(PAGE_STATE_JS) { raw ->
                        note("页面状态: ${raw?.take(300)}")
                    }
                    // 兜底：详情接口没被截到（页面改走别的通道、或我们转发失败）时，
                    // 退一步读页面里 <video> 的地址。只有一档清晰度，但总比失败强。
                    view.postDelayed({
                        // 顺便再采一次状态，这时才看得到页面实际发出了哪些接口
                        view.evaluateJavascript(PAGE_STATE_JS) { raw ->
                            note("8s 后状态: ${raw?.take(400)}")
                        }
                        readVideoTag(view) { settle(it, "video 标签") }
                    }, TAG_FALLBACK_DELAY_MS)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    note("主页 HTTP ${errorResponse?.statusCode}: ${request.url?.toString()?.take(100)}")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    note("主页加载失败: ${error?.errorCode} ${error?.description}")
                    settle(null, "主页加载失败")
                }
            }
        }

        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    host?.dismiss()
                    webView.destroy()
                }
            }
        }

        val target = urlTemplate.replace("{id}", awemeId)
        note("打开: $target")
        webView.loadUrl(target)
    }

    /**
     * 把 WebView 挂到一个真实窗口上，然后整块挪到屏幕外。
     *
     * 为什么不能只用裸 WebView：没有 attach 到窗口的 WebView 不参与渲染循环，
     * `requestAnimationFrame` 不回调、`document.visibilityState` 可能是 hidden。
     * 抖音的页面在这种环境下很可能不发起详情请求，而桌面浏览器里一切正常——
     * 这正是「同样的代码在电脑上跑得通、在手机上永远失败」最典型的成因。
     *
     * 为什么用屏幕外而不是 1×1：1×1 的窗口会让页面视口只有 1×1，
     * 依赖 IntersectionObserver 的懒加载逻辑会全部失灵。给一个正常视口再挪走最稳。
     */
    private fun attachToWindow(webView: WebView): Dialog? {
        val activity = App.currentActivity ?: run {
            note("窗口: 没有前台 Activity，退回离屏模式")
            return null
        }
        return runCatching {
            val dm = activity.resources.displayMetrics
            Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    )
                    setGravity(Gravity.TOP or Gravity.START)
                    attributes = attributes.apply {
                        x = -(dm.widthPixels + OFFSCREEN_GAP)
                        y = 0
                    }
                    setLayout((dm.density * 360).toInt(), (dm.density * 640).toInt())
                }
                setContentView(webView)
                setCanceledOnTouchOutside(false)
                show()
            }.also { note("窗口: 已挂到屏幕外窗口（视口 360×640）") }
        }.getOrElse {
            note("窗口: 挂载失败 ${it.javaClass.simpleName}，退回离屏模式")
            null
        }
    }

    // ---------- 转发 ----------

    private fun matchesDetailApi(url: String): Boolean =
        DetailApiMatcher.matches(url, apiPaths)

    /**
     * 在页面上下文里重新请求同一个详情 URL。
     *
     * 这是整条链路的关键：页面内 fetch 由 WebView 网络栈发出，cookie / Referer /
     * 同源身份全部天然正确，服务端就把它当成页面自己的请求。
     *
     * 而用 OkHttp 自己发时，`CookieManager.getCookie()` 在后台线程经常返回空，
     * 服务端于是回 `200 + 空体`（对照实验见 tools/probe-ua.js：唯一触发空体的条件
     * 就是没有 cookie，UA 与 sec-ch-ua 版本不一致都不影响）。
     */
    private fun jsFetchDetail(url: String): String = """
        (function () {
          try {
            fetch(${JSONObject.quote(url)}, {
              credentials: 'include',
              headers: { 'Accept': 'application/json, text/plain, */*' }
            }).then(function (r) { return r.text(); })
              .then(function (t) {
                if (t && t.indexOf('aweme_detail') >= 0) window.$BRIDGE_NAME.onDetail(t);
              })
              .catch(function (e) {});
          } catch (e) {}
        })();
    """.trimIndent()

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
            note("video 标签: " + if (url.isBlank()) "无" else url.take(100))
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
        private const val TRACE_MAX = 2_000

        /** 屏幕外窗口的额外偏移，保证整块窗口完全落在可视区之外 */
        private const val OFFSCREEN_GAP = 300

        const val DEFAULT_URL_TEMPLATE = "https://www.douyin.com/video/{id}"

        /**
         * 详情接口的候选片段。第一个是当前线上路径；第二个是去掉版本前缀的宽松匹配，
         * 用来扛「接口换版本号」这种小改版。
         */
        val DEFAULT_API_PATHS = listOf(
            "/aweme/v1/web/aweme/detail/",
            "/aweme/detail/"
        )

        /**
         * 采集页面现场。
         *
         * `vis` 是重点：离屏 WebView 的 document.visibilityState 可能是 hidden，
         * 而部分 SPA 会因此在初始化时跳过数据预取——真机失败、桌面浏览器成功，
         * 差别往往就在这里。
         *
         * `aw` 列出页面实际发出的 /aweme/ 接口，用来区分「没发请求」和「发了但被拒」。
         */
        private val PAGE_STATE_JS = """
            (function () {
              try {
                return JSON.stringify({
                  t: document.title || '',
                  rs: document.readyState,
                  vis: document.visibilityState,
                  n: document.documentElement ? document.documentElement.outerHTML.length : 0,
                  v: document.querySelectorAll('video').length,
                  aw: performance.getEntriesByType('resource')
                        .filter(function (e) { return e.name.indexOf('/aweme/') >= 0; })
                        .map(function (e) { return e.name.split('?')[0].slice(-70); })
                        .slice(0, 8)
                });
              } catch (e) { return 'ERR ' + e; }
            })()
        """.trimIndent()

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
