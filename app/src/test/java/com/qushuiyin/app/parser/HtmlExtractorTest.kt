package com.qushuiyin.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HtmlExtractorTest {

    @Test
    fun `能抠出 ROUTER_DATA 对象`() {
        val html = """
            <html><body><script>window._ROUTER_DATA = {"loaderData":{"a":1}};</script></body></html>
        """.trimIndent()
        assertEquals("""{"loaderData":{"a":1}}""", HtmlExtractor.extractObject(html, "_ROUTER_DATA"))
    }

    @Test
    fun `字符串里的花括号不会截断对象`() {
        val html = """<script>window._ROUTER_DATA = {"desc":"他说{你好}","n":2};</script>"""
        val out = HtmlExtractor.extractObject(html, "_ROUTER_DATA")
        assertEquals("""{"desc":"他说{你好}","n":2}""", out)
    }

    @Test
    fun `转义引号不会破坏配平`() {
        val html = """<script>window._ROUTER_DATA = {"desc":"a\"}b","n":2};</script>"""
        val out = HtmlExtractor.extractObject(html, "_ROUTER_DATA")!!
        // 提取出来的必须能被 JSON 解析器接受
        kotlinx.serialization.json.Json.parseToJsonElement(out)
        assertEquals(true, out.endsWith("}"))
    }

    @Test
    fun `没有数据时返回 null`() {
        assertNull(HtmlExtractor.extractObject("<html>验证码</html>", "_ROUTER_DATA"))
    }

    @Test
    fun `RENDER_DATA 需要 URL 解码`() {
        val html = """<script id="RENDER_DATA" type="application/json">%7B%22a%22%3A1%7D</script>"""
        assertEquals("""{"a":1}""", HtmlExtractor.extractRenderData(html))
    }
}
