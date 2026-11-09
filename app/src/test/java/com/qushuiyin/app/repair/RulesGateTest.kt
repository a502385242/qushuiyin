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

    /** 与当前 App 的 versionCode 对齐：规则文件的 minAppVersion 就是按它定的 */
    private val APP_VER = 3

    private fun ready(): Boolean = manifest.exists() && sigFile.exists() && pubFile.exists()
    private fun pub(): String = pubFile.readText().trim()
    private fun raw(): ByteArray = manifest.readBytes()
    private fun sig(): String = sigFile.readText().trim()

    @Test
    fun `真实规则文件能通过校验并被采用`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val d = RulesGate.evaluate(raw(), sig(), pub(), appVersionCode = APP_VER, currentVersion = 3)
        assertTrue("应被采用，实际是 $d", d is RulesGate.Decision.Accept)

        val accepted = d as RulesGate.Decision.Accept
        assertEquals("manifest 的版本号被读错", 4, accepted.rules.schemaVersion)
        assertTrue("候选路径没解析出来", accepted.rules.itemPaths.isNotEmpty())
        assertTrue("规则原文应原样保留，供下次启动复用", accepted.text.contains("itemPaths"))
        assertEquals(
            "线上规则应走 WebView 取数（分享页 SSR 已失效）",
            "webview",
            accepted.rules.sourceMode
        )
        assertTrue(
            "webview 模式下 detailApiPaths 是截获详情的唯一依据，不能为空",
            accepted.rules.detailApiPaths.isNotEmpty()
        )
    }

    @Test
    fun `版本不比本地新时返回 NoUpdate 而不是报错`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val d = RulesGate.evaluate(raw(), sig(), pub(), appVersionCode = APP_VER, currentVersion = 4)
        assertEquals(RulesGate.Decision.NoUpdate, d)
    }

    @Test
    fun `App 版本低于规则要求时拒绝并要求升级`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        // minAppVersion = 3，把 App 版本压到 2 就能触发这条分支
        val d = RulesGate.evaluate(raw(), sig(), pub(), appVersionCode = 2, currentVersion = 1)
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
        val d = RulesGate.evaluate(tampered, sig(), pub(), appVersionCode = 2, currentVersion = 1)
        assertTrue("篡改后竟然被采用", d is RulesGate.Decision.Reject)
        val reason = (d as RulesGate.Decision.Reject).reason
        assertTrue("拒绝理由应指向签名而不是别的，实际是：$reason", reason.contains("签名"))
    }

    @Test
    fun `没内置公钥时一律不采用远程规则`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val d = RulesGate.evaluate(raw(), sig(), publicKeyB64 = "", appVersionCode = 2, currentVersion = 1)
        assertTrue("没公钥竟然还能采用，等于热修复后门", d is RulesGate.Decision.Reject)
    }

    @Test
    fun `空内容被拒绝`() {
        val d = RulesGate.evaluate(ByteArray(0), "AAAA", pub(), appVersionCode = 2, currentVersion = 0)
        assertTrue(d is RulesGate.Decision.Reject)
    }

    @Test
    fun `镜像地址解析会规范化结尾斜杠并过滤非 http 项`() {
        val list = MirrorClient.parseMirrors(
            " https://a.example/config ,https://b.example/x/,not-a-url,https://a.example/config/ "
        )
        assertEquals(listOf("https://a.example/config/", "https://b.example/x/"), list)
    }

    // ---------- 多镜像挑选（回归测试） ----------

    /** 上一版真发过的 v2 规则，签名有效但内容已过时 */
    private fun staleRaw(): ByteArray =
        javaClass.getResourceAsStream("/manifest_v2_stale.json")!!.readBytes()

    private fun staleSig(): String =
        javaClass.getResourceAsStream("/manifest_v2_stale.json.sig")!!
            .bufferedReader().use { it.readText() }.trim()

    @Test
    fun `同时收到旧缓存和新规则时选版本更高的那份`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        // 这就是线上真实发生过的情形：jsDelivr 先返回 12 小时前的 v2，raw 后返回 v3。
        // 旧策略「谁先回用谁」会选中 v2，然后告诉用户「已是最新」。
        val candidates = listOf(
            RulesGate.Candidate(staleRaw(), staleSig(), "jsdelivr(旧缓存)"),
            RulesGate.Candidate(raw(), sig(), "raw")
        )
        val choice = RulesGate.choose(candidates, pub(), appVersionCode = APP_VER, currentVersion = 2)

        assertTrue("应当选中新规则，实际 $choice", choice is RulesGate.Choice.Repaired)
        val repaired = choice as RulesGate.Choice.Repaired
        assertEquals("必须选版本号高的那份", 4, repaired.rules.schemaVersion)
        assertEquals("raw", repaired.source)
    }

    @Test
    fun `候选顺序不影响结果`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val reversed = listOf(
            RulesGate.Candidate(raw(), sig(), "raw"),
            RulesGate.Candidate(staleRaw(), staleSig(), "jsdelivr(旧缓存)")
        )
        val choice = RulesGate.choose(reversed, pub(), appVersionCode = APP_VER, currentVersion = 2)
        assertEquals(4, (choice as RulesGate.Choice.Repaired).rules.schemaVersion)
    }

    @Test
    fun `只有旧缓存时报告无更新而不是采用它`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val choice = RulesGate.choose(
            listOf(RulesGate.Candidate(staleRaw(), staleSig(), "cdn")),
            pub(),
            appVersionCode = APP_VER,
            currentVersion = 2
        )
        assertEquals(RulesGate.Choice.NoUpdate, choice)
    }

    @Test
    fun `全部候选都验签失败时给出拒绝理由`() {
        assumeTrue("本地未生成密钥，跳过", ready())

        val choice = RulesGate.choose(
            listOf(
                RulesGate.Candidate(raw(), "AAAA", "a"),
                RulesGate.Candidate(staleRaw(), "BBBB", "b")
            ),
            pub(),
            appVersionCode = APP_VER,
            currentVersion = 2
        )
        assertTrue(choice is RulesGate.Choice.Rejected)
    }
}
