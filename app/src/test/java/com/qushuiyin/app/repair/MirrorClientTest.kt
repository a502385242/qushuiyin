package com.qushuiyin.app.repair

import com.qushuiyin.app.BuildConfig
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像地址与「防陈旧」参数的测试。
 *
 * 这里测的不是网络，而是两个容易悄悄出错的约定：
 *   1. 镜像地址必须被规范化（结尾斜杠），否则拼出来的 URL 是 `configmanifest.json`
 *   2. 每次请求都要带 cache-buster，否则 jsDelivr 的 12 小时缓存会让热修复形同虚设
 */
class MirrorClientTest {

    private val client = MirrorClient(OkHttpClient(), emptyList())

    @Test
    fun `规则地址会追加按分钟分桶的 cache-buster`() {
        val url = client.freshUrl("https://cdn.example.com/config/manifest.json", bucket = 12345)
        assertEquals("https://cdn.example.com/config/manifest.json?v=12345", url)
    }

    @Test
    fun `同一个桶值给出同一个地址`() {
        val a = client.freshUrl("https://x/manifest.json", 999)
        val b = client.freshUrl("https://x/manifest.json", 999)
        assertEquals("同一分钟内所有用户必须共享同一份缓存", a, b)
    }

    @Test
    fun `已有查询参数的地址用 and 拼接`() {
        val url = client.freshUrl("https://x/manifest.json?a=1", 7)
        assertEquals("https://x/manifest.json?a=1&v=7", url)
    }

    @Test
    fun `内置镜像地址都是可用的 https 目录`() {
        val mirrors = MirrorClient.parseMirrors(BuildConfig.REPAIR_MIRRORS)
        assertTrue("内置镜像为空，修复按钮会永远失败", mirrors.isNotEmpty())
        mirrors.forEach {
            assertTrue("镜像必须以 https 开头：$it", it.startsWith("https://"))
            assertTrue("镜像必须以 / 结尾，否则拼出来的路径是错的：$it", it.endsWith("/"))
        }
        assertEquals("镜像地址不应重复", mirrors.size, mirrors.distinct().size)
    }
}
