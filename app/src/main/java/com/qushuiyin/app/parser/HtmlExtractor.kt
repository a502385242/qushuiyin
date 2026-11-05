package com.qushuiyin.app.parser

import java.net.URLDecoder

/**
 * 从 HTML 里把内嵌的 JSON 抠出来。
 *
 * 抖音分享页把数据直接写在 <script> 里，形如：
 *   <script>window._ROUTER_DATA = {"loaderData":{...}};</script>
 *
 * 不能用正则 `\{.*\}` 抓，因为 JSON 字符串里可能带花括号，会截断。
 * 所以做花括号配平扫描，并且正确跳过字符串内容和转义字符。
 */
object HtmlExtractor {

    /**
     * 在 html 中查找 key 之后第一个 JSON 对象并返回其原文。
     * @param keys 依次尝试的变量名，例如 "_ROUTER_DATA"
     */
    fun extractObject(html: String, vararg keys: String): String? {
        for (key in keys) {
            var from = 0
            while (true) {
                val idx = html.indexOf(key, from)
                if (idx < 0) break

                val braceStart = html.indexOf('{', idx)
                // 变量名和花括号之间不会离太远，超了就认为不是赋值语句
                if (braceStart >= 0 && braceStart - idx < 200) {
                    val end = matchBrace(html, braceStart)
                    if (end > braceStart) return html.substring(braceStart, end + 1)
                }
                from = idx + key.length
            }
        }
        return null
    }

    /** RENDER_DATA 是 URL 编码过的 JSON，需要先解码 */
    fun extractRenderData(html: String): String? {
        val m = Regex("""<script[^>]*id=["']RENDER_DATA["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val raw = m.groupValues[1].trim()
        if (raw.isEmpty()) return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
    }

    /** 返回与 start 处 '{' 配对的 '}' 下标，失败返回 -1 */
    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var quote = ' '
        var escaped = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> { inString = true; quote = c }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }
}
