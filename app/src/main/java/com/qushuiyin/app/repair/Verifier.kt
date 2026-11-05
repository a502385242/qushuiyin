package com.qushuiyin.app.repair

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 配置签名校验。
 *
 * 算法选 ECDSA P-256 而不是 Ed25519：后者在 Android 上要 API 33+，
 * minSdk 26 的话得额外引入 BouncyCastle（包体白涨 1MB+）。
 * P-256 从 API 26 起就是系统原生支持，密钥还更短。
 *
 * 注意：签名对象是 manifest.json 的**原始字节**，不做任何 JSON 规范化。
 * 因此任何一行空白、换行符（CRLF/LF）变化都会导致验签失败 —— 这是刻意的，
 * 避免「规范化规则不一致」导致的偶发验不过。发布前务必用 sign 命令重新签。
 */
object Verifier {

    /**
     * @param raw              配置文件的原始字节
     * @param signatureBase64  分离式签名（DER，Base64）
     * @param publicKeyBase64  SubjectPublicKeyInfo（DER，Base64），由 keygen 产出
     */
    fun verify(
        raw: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        if (signatureBase64.isBlank() || publicKeyBase64.isBlank()) return false
        return runCatching {
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64.trim())
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(raw)
            }
            sig.verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }.getOrDefault(false)
    }
}
