package com.qushuiyin.app.repair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 这一组测试的意义：证明「用 openssl 签的东西，JVM/Android 端验得过」。
 * 跨工具链的签名互通是最容易埋雷的地方，必须用真实产物验证，不能靠推理。
 */
class VerifierTest {

    private val manifest = File("../config/manifest.json")
    private val sigFile = File("../config/manifest.json.sig")
    private val pubFile = File("../keys/public.b64")

    private fun readPub(): String = pubFile.readText().trim()

    @Test
    fun `openssl 生成的签名可以被 JVM 验通`() {
        assumeTrue("本地未生成密钥，跳过", pubFile.exists() && sigFile.exists())

        val ok = Verifier.verify(
            raw = manifest.readBytes(),
            signatureBase64 = sigFile.readText().trim(),
            publicKeyBase64 = readPub()
        )
        assertTrue("跨工具链验签失败：检查签名是否基于原始字节、公钥是否为 DER 格式", ok)
    }

    @Test
    fun `内容被篡改一个字节就验不过`() {
        assumeTrue("本地未生成密钥，跳过", pubFile.exists() && sigFile.exists())

        val tampered = manifest.readBytes().copyOf().also {
            it[it.size / 2] = (it[it.size / 2] + 1).toByte()
        }
        val ok = Verifier.verify(
            raw = tampered,
            signatureBase64 = sigFile.readText().trim(),
            publicKeyBase64 = readPub()
        )
        assertFalse("签名竟然通过了篡改后的内容", ok)
    }

    @Test
    fun `换行符从 LF 变成 CRLF 也会导致验签失败`() {
        assumeTrue("本地未生成密钥，跳过", pubFile.exists() && sigFile.exists())

        val crlf = manifest.readText()
            .replace("\r\n", "\n")
            .replace("\n", "\r\n")
            .toByteArray()
        val ok = Verifier.verify(
            raw = crlf,
            signatureBase64 = sigFile.readText().trim(),
            publicKeyBase64 = readPub()
        )
        assertFalse(
            "CRLF 版本竟然验过了 —— 说明签名对象不是原始字节，需要自查",
            ok
        )
    }

    @Test
    fun `空签名或空公钥直接返回 false`() {
        assertFalse(Verifier.verify(byteArrayOf(1, 2, 3), "", readPubCompat()))
        assertFalse(Verifier.verify(byteArrayOf(1, 2, 3), "AAAA", ""))
    }

    private fun readPubCompat(): String =
        if (pubFile.exists()) readPub() else "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"
}
