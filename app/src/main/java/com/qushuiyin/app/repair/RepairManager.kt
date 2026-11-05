package com.qushuiyin.app.repair

import com.qushuiyin.app.parser.ParseRules

/** 一次「点修复」的结果，直接对应 UI 上要显示的一句话 */
sealed interface RepairOutcome {

    /** 成功换了新规则。带上规则本体，调用方可以直接热替换给解析器，不必再读一次盘 */
    data class Repaired(val rules: ParseRules, val mirror: String) : RepairOutcome

    /** 拉到了、也验过签，但版本不比本地新（通常意味着别人已经推过更高版本，或你推完忘了改 schemaVersion） */
    data object NoUpdate : RepairOutcome

    /** 所有镜像都没拉到（网络问题，不是规则问题） */
    data object Unreachable : RepairOutcome

    /** 拉到了但不可用：验签失败、格式坏、要求更高 App 版本 */
    data class Rejected(val reason: String) : RepairOutcome
}

/**
 * 修复链路的编排：拉取 → 判定 → 落盘。
 *
 * 判定逻辑全在 [RulesGate]（纯 JVM，可单测），这里只负责 I/O 和写入。
 * 一条铁律：**先校验通过再落盘**。反过来的话，一次半截下载就会把本地
 * 本来能用的规则覆盖成坏的，用户从「偶尔解析失败」变成「彻底不能用」。
 */
class RepairManager(
    private val store: RulesStore,
    private val mirrorClient: MirrorClient,
    private val appVersionCode: Int,
    private val publicKey: String
) {

    /** 启动时读取上次修复留下的规则；没有就返回 null，用内置默认值 */
    suspend fun savedRules(): ParseRules? = store.load()

    suspend fun savedVersion(): Int = store.appliedVersion()

    /** 在线修复：并发拉所有镜像，按版本号挑最新的一份采用 */
    suspend fun repair(current: ParseRules): RepairOutcome {
        val bundles = mirrorClient.fetchAll()
        if (bundles.isEmpty()) return RepairOutcome.Unreachable
        val candidates = bundles.map { RulesGate.Candidate(it.raw, it.signature, it.mirror) }
        return decide(candidates, current)
    }

    /**
     * 手动导入：用户从 Issue / 群里拿到的正文和签名。
     * **依然验签**——否则「热心网友」给全体用户塞一份指向钓鱼站点的规则就完了。
     */
    suspend fun import(
        manifestText: String,
        signature: String,
        current: ParseRules
    ): RepairOutcome = decide(
        listOf(
            RulesGate.Candidate(
                raw = manifestText.trim().toByteArray(Charsets.UTF_8),
                signature = signature,
                source = "手动导入"
            )
        ),
        current
    )

    private suspend fun decide(
        candidates: List<RulesGate.Candidate>,
        current: ParseRules
    ): RepairOutcome =
        when (val c = RulesGate.choose(candidates, publicKey, appVersionCode, current.schemaVersion)) {
            is RulesGate.Choice.Repaired -> {
                store.save(c.text, c.rules, c.source)
                RepairOutcome.Repaired(c.rules, c.source)
            }
            RulesGate.Choice.NoUpdate -> RepairOutcome.NoUpdate
            is RulesGate.Choice.Rejected -> RepairOutcome.Rejected(c.reason)
        }
}
