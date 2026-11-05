package com.qushuiyin.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {

    @Test
    fun `整段分享文案里能抽出短链`() {
        val text = "7.94 复制打开抖音，看看【某某的作品】这个视频真好 " +
            "https://v.douyin.com/iRxAbCd/ 复制此链接，打开Dou音搜索，直接观看视频！"
        val t = LinkParser.parse(text)
        assertTrue(t is LinkParser.Target.ShortLink)
        assertEquals("https://v.douyin.com/iRxAbCd", (t as LinkParser.Target.ShortLink).url)
    }

    @Test
    fun `完整视频链接能取出作品 ID`() {
        val t = LinkParser.parse("https://www.douyin.com/video/7300000000000000001?previous_page=app")
        assertEquals("7300000000000000001", (t as LinkParser.Target.AwemeId).id)
    }

    @Test
    fun `modal_id 形式也能识别`() {
        val t = LinkParser.parse("https://www.douyin.com/discover?modal_id=7300000000000000002")
        assertEquals("7300000000000000002", (t as LinkParser.Target.AwemeId).id)
    }

    @Test
    fun `裸的作品 ID 兜底`() {
        val t = LinkParser.parse("7300000000000000003")
        assertEquals("7300000000000000003", (t as LinkParser.Target.AwemeId).id)
    }

    @Test
    fun `无关文字返回 null`() {
        assertNull(LinkParser.parse("今天天气不错"))
        assertNull(LinkParser.parse(""))
    }
}
