package com.qushuiyin.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qushuiyin.app.BuildConfig
import com.qushuiyin.app.core.Http
import com.qushuiyin.app.model.Aweme
import com.qushuiyin.app.parser.DouyinParser
import com.qushuiyin.app.parser.LinkParser
import com.qushuiyin.app.parser.ParseError
import com.qushuiyin.app.parser.WebDetailFetcher
import com.qushuiyin.app.repair.MirrorClient
import com.qushuiyin.app.repair.RepairManager
import com.qushuiyin.app.repair.RepairOutcome
import com.qushuiyin.app.repair.RulesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val input: String = "",
    val parsing: Boolean = false,
    /** 解析进度提示（「正在通过网页加载作品」这类）。WebView 路径要几秒，不给反馈用户会以为卡住 */
    val stage: String? = null,
    val aweme: Aweme? = null,
    val error: ParseError? = null,
    /** 正在拉规则，UI 上按钮转圈 */
    val repairing: Boolean = false,
    /** 一次性提示，展示后清空 */
    val toast: String? = null
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val parser = DouyinParser(
        http = Http.client,
        // 每条规则都可能被热更新，所以按当前规则现造一个来源，而不是启动时固定下来
        detailSourceFactory = { rules ->
            WebDetailFetcher(
                context = app,
                urlTemplate = rules.detailUrlTemplate,
                userAgent = rules.desktopUa,
                apiPaths = rules.detailApiPaths
            )
        }
    )

    private val rulesStore = RulesStore(app)
    private val repairManager = RepairManager(
        store = rulesStore,
        mirrorClient = MirrorClient(Http.client),
        appVersionCode = BuildConfig.VERSION_CODE,
        publicKey = BuildConfig.REPAIR_PUBKEY
    )

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // 启动时把上次修复留下的规则接上。只读本地 DataStore，不联网、不拖慢启动
        viewModelScope.launch {
            repairManager.savedRules()?.let(parser::updateRules)
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text, error = null) }
    }

    /** 从抖音分享进来时调用：填进输入框并自动解析 */
    fun onSharedText(text: String) {
        _state.update { it.copy(input = text, error = null) }
        parse()
    }

    fun parse() {
        val target = LinkParser.parse(_state.value.input)
        if (target == null) {
            _state.update { it.copy(error = ParseError.NoLink, aweme = null) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(parsing = true, error = null, aweme = null, stage = null) }
            when (val r = parser.parse(target) { stage ->
                _state.update { it.copy(stage = stage) }
            }) {
                is DouyinParser.ParseResult.Ok -> {
                    _state.update { it.copy(parsing = false, aweme = r.aweme, stage = null) }
                    prefetchRulesQuietly()
                }
                is DouyinParser.ParseResult.Fail ->
                    _state.update { it.copy(parsing = false, error = r.error, stage = null) }
            }
        }
    }

    /**
     * 失败驱动的修复。
     *
     * 只有 [ParseError.Schema] 才允许走到这里——网络断了也点修复，
     * 用户拿到的只会是「修复失败」，白白消耗他对这个按钮的信任。
     */
    fun repair() {
        val err = _state.value.error
        if (err?.canRepair != true) return
        if (_state.value.repairing) return

        viewModelScope.launch {
            _state.update { it.copy(repairing = true, toast = null) }
            when (val outcome = repairManager.repair(parser.currentRules)) {
                is RepairOutcome.Repaired -> {
                    parser.updateRules(outcome.rules)
                    _state.update {
                        it.copy(
                            repairing = false,
                            toast = "已更新到规则 v${outcome.rules.schemaVersion}，正在重试"
                        )
                    }
                    retryAfterRepair()
                }
                RepairOutcome.NoUpdate -> _state.update {
                    it.copy(
                        repairing = false,
                        toast = "已是最新规则。这次失败可能不是版式变化，换条链接或稍后再试"
                    )
                }
                RepairOutcome.Unreachable -> _state.update {
                    it.copy(
                        repairing = false,
                        toast = "拉取规则失败（网络或镜像不可用）。可手动导入规则"
                    )
                }
                is RepairOutcome.Rejected -> _state.update {
                    it.copy(repairing = false, toast = "规则未采用：${outcome.reason}")
                }
            }
        }
    }

    /** 手动导入：从 Issue / 群里复制来的正文 + 签名，仍然要验签 */
    fun importRules(manifestText: String, signature: String) {
        if (manifestText.isBlank() || signature.isBlank()) {
            _state.update { it.copy(toast = "正文和签名都要填") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(repairing = true, toast = null) }
            when (val outcome = repairManager.import(manifestText, signature, parser.currentRules)) {
                is RepairOutcome.Repaired -> {
                    parser.updateRules(outcome.rules)
                    _state.update {
                        it.copy(
                            repairing = false,
                            toast = "已导入规则 v${outcome.rules.schemaVersion}，正在重试"
                        )
                    }
                    retryAfterRepair()
                }
                RepairOutcome.NoUpdate -> _state.update {
                    it.copy(repairing = false, toast = "这份规则不比当前的新，未应用")
                }
                RepairOutcome.Unreachable -> _state.update {
                    it.copy(repairing = false, toast = "导入失败")
                }
                is RepairOutcome.Rejected -> _state.update {
                    it.copy(repairing = false, toast = "规则未采用：${outcome.reason}")
                }
            }
        }
    }

    /**
     * 解析成功后顺手预取一次规则，不提示。
     *
     * 目的：用户真正撞上「版式变了」的那一刻，本地规则已经是新的，点修复几乎瞬时完成。
     * 这不是「启动时检查更新」——不阻塞启动，也不打扰用户。
     */
    private fun prefetchRulesQuietly() {
        viewModelScope.launch {
            val since = rulesStore.lastCheckAt()
            if (System.currentTimeMillis() - since < PREFETCH_INTERVAL_MS) return@launch
            rulesStore.markChecked()
            val outcome = repairManager.repair(parser.currentRules)
            if (outcome is RepairOutcome.Repaired) {
                parser.updateRules(outcome.rules)
            }
        }
    }

    private suspend fun retryAfterRepair() {
        val target = LinkParser.parse(_state.value.input) ?: return
        _state.update { it.copy(parsing = true, error = null, aweme = null, stage = null) }
        when (val r = parser.parse(target) { stage -> _state.update { it.copy(stage = stage) } }) {
            is DouyinParser.ParseResult.Ok ->
                _state.update { it.copy(parsing = false, aweme = r.aweme, stage = null, toast = null) }
            is DouyinParser.ParseResult.Fail ->
                _state.update {
                    it.copy(
                        parsing = false,
                        error = r.error,
                        stage = null,
                        toast = "换了新规则仍然解析不了，可以复制诊断信息反馈"
                    )
                }
        }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    private companion object {
        /** 预取间隔：6 小时。抖音不会一天改三次版式，太频繁只是白耗流量 */
        const val PREFETCH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
