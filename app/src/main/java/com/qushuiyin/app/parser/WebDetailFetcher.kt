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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.qushuiyin.app.App
import com.qushuiyin.app.core.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 取一条**无水印**的播放地址。
 *
 * ## 为什么换掉了原来的方案（0.4.0）
 *
 * 0.2~0.3 一直在啃 `aweme/v1/web/aweme/detail` 这个桌面接口。它要求 `a_bogus` 签名，
 * 而签名之外抖音还会校验「客户端身份是否自洽」：UA 说自己是 Windows Chrome/120，
 * 客户端提示（`navigator.userAgentData` / `sec-ch-ua`）却如实报告 Android 143。
 * 只要有这个矛盾，接口就不报错、**只回 200 + 0 字节**，或者干脆把整页 HTML 吐回来
 * （实测 830 KB）。在 Android WebView 里这个矛盾无法消除——UA 能改，客户端提示改不了。
 * 所以那条路在真机上永远走不通，问题不在实现，在方向。
 *
 * ## 现在的方案
 *
 * 只用两个不设防的入口：
 *
 * ```
 * ① 移动分享页（WebView 渲染）→ 从页面里读出 video_id
 * ② GET aweme.snssdk.com/aweme/v1/play/?video_id=... → 302 → CDN 上的无水印 mp4
 * ```
 *
 * 第 ② 步不需要签名、不需要 cookie、不需要登录，实测 1080×1920、1050 万字节，
 * 抽帧肉眼确认无水印（`tools/probe-mobile.js` + ffmpeg 抽帧）。`ratio` 参数被服务端
 * 忽略——给的一律是它能提供的最高档，所以只请求一次。
 *
 * 第 ① 步为什么必须靠 WebView：`video_id`（形如 `v0200fg10000...`）不在任何
 * 纯 HTTP 响应里，页面 JS 现场才算出来。好在移动页**不需要签名**，只要 UA 与客户端
 * 提示自洽（不覆写 UA 即自洽）就能正常渲染。
 *
 * ## 产物
 *
 * 拼一份与旧详情接口同构的 JSON 交给 [DouyinParser]，这样上层解析、UI、后续下载
 * 都不用改。字段只放我们真正验证过的：desc / cover / play_addr / images。
 */
class WebDetailFetcher(
    context: Context,
    private val urlTemplate: String = DEFAULT_URL_TEMPLATE,
    private val http: OkHttpClient = Http.client
) : DetailSource {

    private val appContext = context.applicationContext

    /** 探测 play 地址走 IO，WebView 操作走主线程 */
    private val work = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sb = StringBuilder()

    /** 给 UI 看的取证记录。多线程写入，必须加锁 */
    val trace: String get() = synchronized(sb) { sb.toString() }

    private fun note(msg: String) = synchronized(sb) {
        if (sb.length < TRACE_MAX) sb.append(msg).append('\n')
    }

    override suspend fun fetch(awemeId: String): String? {
        note("ua: " + systemWebViewVersion())
        return withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main) { load(awemeId) }
        } ?: run {
            note("done: 超时 ${TIMEOUT_MS / 1000}s，页面没给出 video_id")
            null
        }
    }

    private fun systemWebViewVersion(): String = runCatching {
        val p = WebView.getCurrentWebViewPackage()
        if (p == null) "无法获取" else "${p.packageName} ${p.versionName}"
    }.getOrDefault("无法获取")

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(awemeId: String): String? = suspendCancellableCoroutine { cont ->
        val webView = WebView(appContext)
        val settled = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        val host = attachToWindow(webView)
        val handler = Handler(Looper.getMainLooper())

        fun settle(result: String?, by: String) {
            if (!settled.compareAndSet(false, true)) return
            note("done: $by -> " + if (result == null) "空" else "${result.length} 字节")
            // 不能立刻 destroy：此刻 WebView 可能正拿着我们刚返回的响应流在读
            handler.postDelayed({
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    host?.dismiss()
                    webView.destroy()
                }
            }, DESTROY_DELAY_MS)
            if (cont.isActive) cont.resume(result)
        }

        /**
         * 拿到 video_id，去 play 接口验一次再返回。
         *
         * 验一次的价值：video_id 可能是从页面里抠错了、或者片子已删，只有真发一个
         * Range 请求才知道服务端认不认。验不过就退回复位，让后面的采样继续试。
         */
        fun resolve(vid: String, page: PageInfo, by: String) {
            if (settled.get() || !resolving.compareAndSet(false, true)) return
            note("video_id($by): ${vid.take(24)}")
            work.launch {
                val probe = probePlay(vid)
                if (probe == null) {
                    note("play 接口没通过，继续等页面")
                    resolving.set(false)
                    return@launch
                }
                val json = buildDetailJson(awemeId, probe, page).toString()
                settle(json, "play 接口实测通过（${probe.label}）")
            }
        }

        fun resolveImages(page: PageInfo) {
            if (settled.get() || !resolving.compareAndSet(false, true)) return
            if (page.images.isEmpty()) {
                resolving.set(false)
                return
            }
            note("按图文作品处理，${page.images.size} 张图")
            val detail = JSONObject()
                .put("aweme_id", awemeId)
                .put("desc", cleanTitle(page.title))
                .put(
                    "images",
                    JSONArray(page.images.map { JSONObject().put("url_list", JSONArray().put(it)) })
                )
            if (page.cover.isNotBlank()) {
                detail.put("video", JSONObject().put("cover", JSONObject().put("url_list", JSONArray().put(page.cover))))
            }
            settle(JSONObject().put("aweme_detail", detail).toString(), "图文作品")
        }

        // ---------- 采样：页面每渲染一步就试一次 ----------

        val poller = object : Runnable {
            private var n = 0

            override fun run() {
                if (settled.get() || n >= MAX_POLLS) return
                n++
                webView.evaluateJavascript(PROBE_JS) { raw ->
                    val page = PageInfo.parse(raw)
                    if (page == null) {
                        note("采样 $n: 读不到页面")
                        return@evaluateJavascript
                    }
                    when {
                        page.videoId.isNotBlank() -> resolve(page.videoId, page, "采样 $n")
                        page.videoCount == 0 && page.images.isNotEmpty() -> resolveImages(page)
                        else -> note("采样 $n: 无 video_id（title=${page.title.take(16)}, video=${page.videoCount}）")
                    }
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 图片照常加载：页面要靠自己的 JS 把播放器挂起来，video_id 才会出现
            blockNetworkImage = false
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // **刻意不覆写 UA**。覆写成桌面 UA 会让 UA 与客户端提示互相矛盾，
            // 抖音那边直接就回空体了（这是 0.2~0.3 一直失败的真因）。
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                note("开始加载: ${url?.take(100)}")
            }

            /**
             * 抢跑：播放器一定会去请求 `.../playwm/?...&video_id=xxx`，
             * 这条请求比 DOM 里出现 `<video src>` 更早，从这里直接薅出 video_id 最快。
             * 注意只取 id，**不用它返回的地址**——`playwm` 是带水印的那条。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.contains("video_id=")) return null
                if (!url.contains("/play") && !url.contains("/aweme/v1/play")) return null
                val vid = VIDEO_ID_REGEX.find(url)?.groupValues?.getOrNull(1) ?: return null
                view?.post { resolve(vid, PageInfo.empty(), "播放器请求") }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                note("加载完成: ${url?.take(100)}")
                if (settled.get()) return
                handler.removeCallbacks(poller)
                handler.post(poller)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    note("主页 HTTP ${errorResponse?.statusCode}: ${request.url?.toString()?.take(80)}")
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
            handler.removeCallbacks(poller)
            handler.post {
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

    // ---------- play 接口 ----------

    private class Probe(val url: String, val size: Long, val label: String)

    /**
     * 用 `Range: bytes=0-1` 只取 2 字节，拿到 206 + `Content-Range` 里的总长度。
     * 总长度同时当作「档位」展示——它比分辨率好判断，也确实是画质的直接体现。
     */
    private fun probePlay(vid: String): Probe? {
        for (host in PLAY_HOSTS) {
            val url = "https://$host/aweme/v1/play/?video_id=$vid&ratio=1080p&line=0"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", HTTP_UA)
                .header("Range", "bytes=0-1")
                .build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val size = contentRangeTotal(resp.header("Content-Range"))
                        ?: resp.body?.contentLength()?.takeIf { it > 0 }
                        ?: 0L
                    val ct = resp.header("Content-Type").orEmpty()
                    val ok = resp.isSuccessful && (size > 0 || ct.startsWith("video/"))
                    note("play $host: HTTP ${resp.code} ${humanSize(size)} $ct")
                    if (ok) return Probe(url, size, humanSize(size))
                }
            }.onFailure { note("play $host 异常: ${it.javaClass.simpleName}") }
        }
        return null
    }

    private fun contentRangeTotal(header: String?): Long? {
        val v = header ?: return null
        val i = v.lastIndexOf('/')
        if (i < 0) return null
        return v.substring(i + 1).trim().toLongOrNull()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "未知"
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    }

    /**
     * 拼一份与旧 `aweme/detail` 同构的 JSON。
     *
     * `bit_rate` 里塞的是文件字节数：上层按它从高到低排序选清晰度，字节数作为
     * 画质代理足够可靠（同一段视频，1080p 一定比 720p 大）。
     */
    private fun buildDetailJson(awemeId: String, probe: Probe, page: PageInfo): JSONObject {
        val playAddr = JSONObject()
            .put("url_list", JSONArray().put(probe.url))
            .put("data_size", probe.size)
        val video = JSONObject()
            .put("play_addr", playAddr)
            .put(
                "bit_rate",
                JSONArray().put(
                    JSONObject()
                        .put("bit_rate", probe.size)
                        .put("gear_name", probe.label)
                        .put("play_addr", JSONObject().put("url_list", JSONArray().put(probe.url)))
                )
            )
        if (page.cover.isNotBlank()) {
            video.put("cover", JSONObject().put("url_list", JSONArray().put(page.cover)))
        }
        if (page.images.isNotEmpty()) {
            video.put("images", JSONArray(page.images.map { JSONObject().put("url_list", JSONArray().put(it)) }))
        }
        val detail = JSONObject()
            .put("aweme_id", awemeId)
            .put("desc", cleanTitle(page.title))
            .put("video", video)
        return JSONObject().put("aweme_detail", detail)
    }

    private fun cleanTitle(raw: String): String =
        raw.removeSuffix(" - 抖音").removeSuffix("-抖音").trim()

    /** 页面采样结果 */
    private data class PageInfo(
        val title: String,
        val cover: String,
        val videoId: String,
        val videoCount: Int,
        val images: List<String>
    ) {
        companion object {
            fun empty() = PageInfo("", "", "", -1, emptyList())

            /** evaluateJavascript 返回的是 JS 值的 JSON 表示，这里直接按对象解析 */
            fun parse(raw: String?): PageInfo? {
                if (raw.isNullOrBlank() || raw == "null") return null
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
                val imgs = ArrayList<String>()
                o.optJSONArray("imgs")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) imgs.add(s)
                    }
                }
                return PageInfo(
                    title = o.optString("title"),
                    cover = o.optString("cover"),
                    videoId = o.optString("vid"),
                    videoCount = o.optInt("v", -1),
                    images = imgs
                )
            }
        }
    }

    /**
     * 把 WebView 挂到一个真实窗口上，然后整块挪到屏幕外。
     *
     * 没有 attach 到窗口的 WebView 不参与渲染循环，`requestAnimationFrame` 不回调，
     * 页面可能因此不初始化播放器；而视频地址正是播放器起来之后才有的。
     * 用屏幕外而不是 1×1：1×1 的视口会让懒加载逻辑全部失灵。
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

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val POLL_INTERVAL_MS = 1_200L
        private const val MAX_POLLS = 12
        private const val DESTROY_DELAY_MS = 500L
        private const val TRACE_MAX = 4_000
        private const val OFFSCREEN_GAP = 300

        /** 分享页。移动 UA 下会 302 到 m.douyin.com/share/video/{id}，这里直接打终点少一跳 */
        const val DEFAULT_URL_TEMPLATE = "https://www.douyin.com/video/{id}"

        /** play 接口的候选域名，一个不通换下一个 */
        private val PLAY_HOSTS = listOf("aweme.snssdk.com", "api.amemv.com")

        private const val HTTP_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"

        private val VIDEO_ID_REGEX = Regex("""[?&]video_id=([A-Za-z0-9_-]{10,})""")

        /**
         * 页面采样。三件事：
         *  1. 从 DOM / HTML / `<video src>` 里抠 `video_id`
         *  2. 拿标题和封面（og:image），UI 要显示
         *  3. 图文作品没有 video_id，退回收集图片地址
         */
        private val PROBE_JS = """
            (function () {
              var out = { title: '', cover: '', vid: '', v: 0, imgs: [] };
              try {
                out.title = document.title || '';
                var m = document.querySelector('meta[property="og:image"]');
                if (m) out.cover = m.getAttribute('content') || '';
                var videos = document.querySelectorAll('video');
                out.v = videos.length;
                var v = videos[0];
                var src = v ? (v.currentSrc || v.src || '') : '';
                if (!out.vid && src) {
                  var a = src.match(/[?&]video_id=([A-Za-z0-9_-]+)/);
                  if (a) out.vid = a[1];
                }
                if (!out.vid) {
                  var html = document.documentElement ? document.documentElement.outerHTML : '';
                  var b = html.match(/[?&]video_id=([A-Za-z0-9_-]{10,})/);
                  if (b) out.vid = b[1];
                }
                if (!out.vid) {
                  var html2 = document.documentElement ? document.documentElement.outerHTML : '';
                  var c = html2.match(/"video_id"\s*:\s*"([A-Za-z0-9_-]{10,})"/);
                  if (c) out.vid = c[1];
                }
                if (out.v === 0) {
                  var imgs = document.querySelectorAll('img');
                  for (var i = 0; i < imgs.length && out.imgs.length < 9; i++) {
                    var u = imgs[i].currentSrc || imgs[i].src || '';
                    if (u.indexOf('douyinpic') > 0 && u.indexOf('avatar') < 0 && u.indexOf('100x100') < 0) {
                      out.imgs.push(u);
                    }
                  }
                }
              } catch (e) {}
              return out;
            })()
        """.trimIndent()
    }
}
