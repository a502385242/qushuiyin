package com.qushuiyin.app.repair

import com.qushuiyin.app.parser.ParseRules
import kotlinx.serialization.json.Json

/**
 * 「这份远程配置到底要不要采用」的全部判断逻辑。
 *
 * 刻意做成**不依赖 Android** 的纯对象：DataStore、OkHttp、BuildConfig 都在外面，
 * 这里只吃字节和版本号。好处是这些判断能直接用 JVM 单测覆盖——
 * 而它们恰恰是热修复方案里最不能出错的地方（验签被绕过、规则把别人的 App 打坏）。
 *
 * 判断顺序是有讲究的，不要随意调整：
 *   验签 → 格式 → App 版本 → 规则版本 → 字段完整性
 * 先验签是因为：未签名的内容连解析都不该做（避免畸形 JSON 触发的解析器漏洞）；
 * 先比 App 版本再看规则版本，是因为「规则太新」比「规则没更新」更值得告诉用户去升 App。
 */
object RulesGate {

    val json: Json = Json {
        ignoreUnknownKeys = true   // 远端随时可能加字段，不能因此整份作废
        isLenient = false
        explicitNulls = false
    }

    sealed interface Decision {
        /** 可以采用 */
        data class Accept(val rules: ParseRules, val text: String) : Decision

        /** 验过签、也解析成功，但版本不比本地新——不算失败，只是没有更新 */
        data object NoUpdate : Decision

        /**
         * 拒绝采用。
         * @param retryable 换镜像重试有没有意义。验签失败、格式错误换镜像也没用，值得直接报给用户。
         */
        data class Reject(val reason: String, val retryable: Boolean = false) : Decision
    }

    /** 一个候选：某个镜像返回的原始字节 + 它的签名 + 来源（诊断用） */
    class Candidate(val raw: ByteArray, val signature: String, val source: String)

    sealed interface Choice {
        data class Repaired(val rules: ParseRules, val text: String, val source: String) : Choice
        data object NoUpdate : Choice
        data class Rejected(val reason: String) : Choice
    }

    /**
     * 从多个镜像的候选里挑一份采用。
     *
     * **不能取「最先返回的那份」**，这是踩过的坑：jsDelivr 对 `@main` 有约 12 小时缓存，
     * 而且它**不认 query 参数**（加 `?v=` 一样返回旧内容）。镜像竞速时 CDN 往往比
     * raw.githubusercontent.com 先回，于是旧规则永远赢过新规则——你推了 v3，
     * 所有用户点修复拿到的还是 v2，还提示「已是最新」。
     *
     * 所以策略改成：收齐窗口内所有响应，**取版本号最大的那份**。
     * 旧缓存于是变成一个无害的噪声，而不是决策依据。
     */
    fun choose(
        candidates: List<Candidate>,
        publicKeyB64: String,
        appVersionCode: Int,
        currentVersion: Int
    ): Choice {
        var best: Choice.Repaired? = null
        var sawNoUpdate = false
        var lastRejectReason: String? = null

        for (c in candidates) {
            when (val d = evaluate(c.raw, c.signature, publicKeyB64, appVersionCode, currentVersion)) {
                is Decision.Accept -> {
                    if (best == null || d.rules.schemaVersion > best!!.rules.schemaVersion) {
                        best = Choice.Repaired(d.rules, d.text, c.source)
                    }
                }
                Decision.NoUpdate -> sawNoUpdate = true
                is Decision.Reject -> lastRejectReason = d.reason
            }
        }

        return best
            ?: if (sawNoUpdate) Choice.NoUpdate
            else Choice.Rejected(lastRejectReason ?: "没有可用的规则")
    }

    /**
     * @param raw            配置文件的**原始字节**（必须与签名时的字节完全一致）
     * @param signature      manifest.json.sig 的内容（Base64 DER）
     * @param publicKeyB64   内置公钥
     * @param appVersionCode 当前 App 的 versionCode
     * @param currentVersion 当前正在用的规则版本
     */
    fun evaluate(
        raw: ByteArray,
        signature: String,
        publicKeyB64: String,
        appVersionCode: Int,
        currentVersion: Int
    ): Decision {
        if (raw.isEmpty()) return Decision.Reject("配置文件为空")
        if (publicKeyB64.isBlank()) return Decision.Reject("App 未内置校验公钥，无法应用远程规则")

        if (!Verifier.verify(raw, signature, publicKeyB64)) {
            return Decision.Reject("签名校验失败，配置可能被篡改或已过期")
        }

        val text = String(raw, Charsets.UTF_8)
        val rules = runCatching { json.decodeFromString<ParseRules>(text) }.getOrNull()
            ?: return Decision.Reject("配置格式不合法")

        if (rules.minAppVersion > appVersionCode) {
            return Decision.Reject("该规则要求 App 版本 ≥ ${rules.minAppVersion}，请先更新 App")
        }

        // 必要的结构性字段，缺一个就不用（防止有人签了一份空壳配置让所有人解析全废）
        if (rules.shareHosts.isEmpty() || rules.itemPaths.isEmpty() || rules.videoUrlPaths.isEmpty()) {
            return Decision.Reject("配置缺少必要字段（shareHosts / itemPaths / videoUrlPaths）")
        }

        // webview 模式下靠 detailApiPaths 认接口，空了就永远截不到请求 —— 等于整份规则报废
        if (rules.sourceMode == ParseRules.SOURCE_WEBVIEW && rules.detailApiPaths.isEmpty()) {
            return Decision.Reject("webview 模式下 detailApiPaths 不能为空")
        }

        if (rules.schemaVersion <= currentVersion) return Decision.NoUpdate

        return Decision.Accept(rules, text)
    }
}
