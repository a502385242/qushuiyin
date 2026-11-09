package com.qushuiyin.app.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对着**线上真实响应的结构**测解析。
 *
 * fixture 是 2026-11-06 从 `aweme/video/detail` 截获的响应裁剪而来：
 * 真实字段名、真实层级、真实的三档清晰度，只把会过期的签名 URL 换成了固定地址。
 *
 * 这组测试的意义：上一版解析器是按「分享页 HTML 内嵌 _ROUTER_DATA」写的，
 * 而线上早就改成接口返回了——那种错误只有拿真数据测才能发现。
 */
class DouyinParserTest {

    private val parser = DouyinParser(OkHttpClient())

    private fun fixture(): String =
        javaClass.getResourceAsStream("/detail_web_2026_11.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `桌面详情响应能解出作品信息`() {
        val r = parser.parseDetailJson(fixture(), "7699062672146337075")
        assertTrue("应解析成功，实际 $r", r is DouyinParser.ParseResult.Ok)

        val a = (r as DouyinParser.ParseResult.Ok).aweme
        assertEquals("7699062672146337075", a.id)
        assertEquals("疯狂水世界", a.authorName)
        assertTrue("标题应包含话题标签，实际：${a.desc}", a.desc.contains("疯狂水世界"))
        assertFalse("这是视频作品，不该被当成图文", a.isImagePost)
    }

    @Test
    fun `按码率降序排列，第一个是 1080 档`() {
        val a = (parser.parseDetailJson(fixture(), "x") as DouyinParser.ParseResult.Ok).aweme
        // 三档码率 + 兜底的 video.play_addr 一条（bit_rate 缺失时靠它，不能删）
        assertEquals("地址数量不对", 4, a.videoUrls.size)
        assertTrue(
            "第一档应是 1080 那条，实际：${a.bestVideoUrl}",
            a.bestVideoUrl!!.endsWith("normal_1080_0.mp4")
        )
        assertTrue("第二档应是 720", a.videoUrls[1].endsWith("normal_720_0.mp4"))
        assertTrue("第三档应是 540", a.videoUrls[2].endsWith("540_2_1.mp4"))
        assertTrue("最后一条应是兜底的 play_addr", a.videoUrls[3].contains("default.mp4"))
    }

    @Test
    fun `download_addr 这种带水印的地址不会被当成首选`() {
        val a = (parser.parseDetailJson(fixture(), "x") as DouyinParser.ParseResult.Ok).aweme
        assertFalse(
            "首选地址里出现了 logo 变体，说明取错了字段",
            a.videoUrls.any { it.contains("logo") }
        )
    }

    @Test
    fun `接口返回非零状态码时归类为未知错误而不是版式变更`() {
        val r = parser.parseDetailJson("""{"status_code":2154,"status_msg":"blocked"}""", "x")
        assertTrue(r is DouyinParser.ParseResult.Fail)
        val e = (r as DouyinParser.ParseResult.Fail).error
        assertEquals(ParseError.Unknown::class, e::class)
        assertFalse("接口报错不该让用户看到一个修不好的修复按钮", e.canRepair)
    }

    @Test
    fun `结构对不上时归类为 Schema，才允许触发在线修复`() {
        val r = parser.parseDetailJson("""{"something":"else"}""", "x")
        assertTrue(r is DouyinParser.ParseResult.Fail)
        val e = (r as DouyinParser.ParseResult.Fail).error
        assertTrue("结构变了才该给出修复入口", e.canRepair)
    }

    @Test
    fun `不是 JSON 时不会抛异常`() {
        val r = parser.parseDetailJson("<html>blocked</html>", "x")
        assertTrue(r is DouyinParser.ParseResult.Fail)
    }

    /**
     * 0.4.0 起不再取桌面详情接口，而是 WebView 抠出 video_id 后去 play 接口拿地址，
     * 再拼一份同构 JSON 交给解析器。这条测试锁住那份合成 JSON 的字段形状——
     * 一旦有人改了合成逻辑而解析器的候选路径没跟上，这里会先红。
     */
    @Test
    fun `play 接口合成的详情能解出无水印地址`() {
        val play = "https://aweme.snssdk.com/aweme/v1/play/?video_id=v0200fg10000dbc910vog65sp6ld28d0&ratio=1080p&line=0"
        val json = """
            {"aweme_detail":{"aweme_id":"7699062672146337075","desc":"好日子也是让我过上了 #疯狂水世界",
             "video":{"cover":{"url_list":["https://p3.douyinpic.com/cover.jpeg"]},
              "play_addr":{"url_list":["$play"],"data_size":10120457},
              "bit_rate":[{"bit_rate":10120457,"gear_name":"9.7MB",
                           "play_addr":{"url_list":["$play"]}}]}}}
        """.trimIndent()

        val r = parser.parseDetailJson(json, "7699062672146337075")
        assertTrue("合成 JSON 必须能被解析，实际 $r", r is DouyinParser.ParseResult.Ok)
        val a = (r as DouyinParser.ParseResult.Ok).aweme

        assertEquals("7699062672146337075", a.id)
        assertEquals("好日子也是让我过上了 #疯狂水世界", a.desc)
        assertEquals("https://p3.douyinpic.com/cover.jpeg", a.coverUrl)
        assertFalse(a.isImagePost)
        // bit_rate 与 play_addr 是同一条地址，去重后只该剩一个
        assertEquals("同一地址不该重复出现", 1, a.videoUrls.size)
        assertEquals(play, a.bestVideoUrl)
        assertFalse("play 地址一旦被改写成 playwm 就会带水印", a.bestVideoUrl!!.contains("playwm"))
    }
}
