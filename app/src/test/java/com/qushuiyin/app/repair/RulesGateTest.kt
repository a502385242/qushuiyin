package com.qushuiyin.app.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 修复链路的判定逻辑测试。
 *
 * 用**真实的** manifest.json / .sig / 公钥，而不是构造的假数据——
 * 这几份文件就是用户手机上会拿到的字节，测它们等于测线上链路。
 */
class RulesGateTest {

    private val manifest = File("../config/manifest.json")
    private val sigFile = File("../config/manifest.json.sig")
    private val pubFile = File("../keys/public.b64")

    private fun ready(): Boolean = manifest.exists() && sigFile.exists() && pubFile.exists()
    private fun pub(): String = pubFile.readText().trim()
    private fun raw(): ByteArray = manifest.readBytes()
    private fun sig(): String = sigFile.readText().trim()

    @Test
    fun `真实规则文件能通过校验并被采用`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val d = RulesGate.evaluate(raw(), sig(), pub(), appVersionCode = 2, currentVersion = 1)
        assertTrue("应被采用，实际是 $d", d is RulesGate.Decision.Accept)

        val accepted = d as RulesGate.Decision.Accept
        assertEquals("manifest 的版本号被读错", 3, accepted.rules.schemaVersion)
        assertTrue("候选路径没解析出来", accepted.rules.itemPaths.isNotEmpty())
        assertTrue("规则原文应原样保留，供下次启动复用", accepted.text.contains("itemPaths"))
        assertEquals(
            "线上规则应走 WebView 取数（分享页 SSR 已失效）",
            "webview",
            accepted.rules.sourceMode
        )
    }

    @Test
    fun `版本不比本地新时返回 NoUpdate 而不是报错`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val d = RulesGate.evaluate(raw(), sig(), pub(), appVersionCode = 2, currentVersion = 3)
        assertEquals(RulesGate.Decision.NoUpdate, d)
    }

    @Test
    fun `App 版本低于规则要求时拒绝并要求升级`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        // minAppVersion = 2，把 App 版本压到 1 就能触发这条分支
        val d = RulesGate.evaluate(raw(), sig(), pub(), appVersionCode = 1, currentVersion = 1)
        assertTrue("应当是拒绝，实际是 $d", d is RulesGate.Decision.Reject)
        val reason = (d as RulesGate.Decision.Reject).reason
        assertTrue("提示里应告诉用户去升级 App，实际是：$reason", reason.contains("版本"))
    }

    @Test
    fun `内容被改动后不会采用`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val tampered = raw().copyOf().also {
            it[it.size / 3] = (it[it.size / 3] + 1).toByte()
        }
        val d = RulesGate.evaluate(tampered, sig(), pub(), appVersionCode = 1, currentVersion = 1)
        assertTrue("篡改后竟然被采用", d is RulesGate.Decision.Reject)
        val reason = (d as RulesGate.Decision.Reject).reason
        assertTrue("拒绝理由应指向签名而不是别的，实际是：$reason", reason.contains("签名"))
    }

    @Test
    fun `没内置公钥时一律不采用远程规则`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val d = RulesGate.evaluate(raw(), sig(), publicKeyB64 = "", appVersionCode = 1, currentVersion = 1)
        assertTrue("没公钥竟然还能采用，等于热修复后门", d is RulesGate.Decision.Reject)
    }

    @Test
    fun `空内容被拒绝`() {
        val d = RulesGate.evaluate(ByteArray(0), "AAAA", pub(), appVersionCode = 1, currentVersion = 0)
        assertTrue(d is RulesGate.Decision.Reject)
    }

    @Test
    fun `镜像地址解析会规范化结尾斜杠并过滤非 http 项`() {
        val list = MirrorClient.parseMirrors(
            " https://a.example/config ,https://b.example/x/,not-a-url,https://a.example/config/ "
        )
        assertEquals(listOf("https://a.example/config/", "https://b.example/x/"), list)
    }
}
