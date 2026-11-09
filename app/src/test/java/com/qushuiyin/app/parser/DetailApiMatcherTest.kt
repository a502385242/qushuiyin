package com.qushuiyin.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailApiMatcherTest {

    private val patterns = listOf("/aweme/v1/web/aweme/detail/", "/aweme/detail/")

    /** 线上真实请求，抓自 2026-11 桌面版抖音 */
    private val realUrl =
        "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383" +
            "&aweme_id=7699062672146337075&origin_type=video_page&a_bogus=E64RhqS7OZ8"

    @Test
    fun `命中线上详情接口`() {
        assertTrue(DetailApiMatcher.matches(realUrl, patterns))
    }

    @Test
    fun `只管路径不管 query`() {
        // 推荐接口也会带 origin_type=detail，若拿整串匹配就会误伤，把页面拖慢甚至搞崩
        val related = "https://www.douyin.com/aweme/v1/web/aweme/related/" +
            "?aweme_id=7699062672146337075&origin_type=detail"
        assertFalse(DetailApiMatcher.matches(related, patterns))
    }

    @Test
    fun `非 http 地址不匹配`() {
        assertFalse(DetailApiMatcher.matches("file:///android_asset/detail/", patterns))
        assertFalse(DetailApiMatcher.matches("blob:https://www.douyin.com/aweme/detail/", patterns))
        assertFalse(DetailApiMatcher.matches("", patterns))
    }

    @Test
    fun `空规则不会把所有请求都吞掉`() {
        assertFalse(DetailApiMatcher.matches(realUrl, emptyList()))
        assertFalse(DetailApiMatcher.matches(realUrl, listOf("", "  ")))
    }

    @Test
    fun `宽松片段能扛住接口换版本号`() {
        val v2 = "https://www.douyin.com/aweme/v2/web/aweme/detail/?aweme_id=1"
        assertTrue(DetailApiMatcher.matches(v2, listOf("/aweme/detail/")))
    }

    @Test
    fun `带井号的地址也能正确切出路径`() {
        assertTrue(DetailApiMatcher.matches(realUrl + "#comment", patterns))
    }
}
