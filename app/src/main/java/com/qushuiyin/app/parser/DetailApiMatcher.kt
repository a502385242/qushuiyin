package com.qushuiyin.app.parser

/**
 * 判断某个 URL 是不是「作品详情接口」。
 *
 * 单独抽出来是为了能单测：这段判断一旦写错（比如拿整个 URL 去匹配，
 * 于是 `...?origin_type=detail` 也命中），WebView 会把一堆无关请求都转发一遍，
 * 页面变慢、还可能因为返回体不对把页面搞崩。而它所在的 [WebDetailFetcher]
 * 依赖 android.webkit，在 JVM 单测里根本加载不了。
 */
internal object DetailApiMatcher {

    /**
     * @param url      完整请求地址
     * @param patterns 详情接口的路径片段（来自解析规则）
     */
    fun matches(url: String, patterns: List<String>): Boolean {
        if (!url.startsWith("http")) return false
        // 只取路径：query 里同样可能带 detail 字样（related 接口就有 origin_type=detail）
        val path = url.substringBefore('?').substringBefore('#')
        return patterns.any { it.isNotBlank() && path.contains(it) }
    }
}
