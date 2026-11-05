package com.qushuiyin.app.repair

import com.qushuiyin.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 镜像地址的约定。
 *
 * 这里没有「cache-buster 生效」的测试，因为实测证明 jsDelivr **不认 query 参数**：
 * 加 `?v=` 一样返回 12 小时前的旧内容。防陈旧改由 [RulesGate.choose] 按版本号挑选来解决，
 * 对应的回归测试在 RulesGateTest 里。
 */
class MirrorClientTest {

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

    @Test
    fun `末尾没有斜杠的地址会被补上`() {
        val list = MirrorClient.parseMirrors("https://a.example/config")
        assertEquals(listOf("https://a.example/config/"), list)
    }

    @Test
    fun `非 http 项会被过滤掉`() {
        val list = MirrorClient.parseMirrors("not-a-url,ftp://x/,https://ok.example/cfg/")
        assertEquals(listOf("https://ok.example/cfg/"), list)
    }
}
