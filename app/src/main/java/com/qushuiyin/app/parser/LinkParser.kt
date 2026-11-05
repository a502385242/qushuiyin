package com.qushuiyin.app.parser

/**
 * 从分享文案里抽取抖音作品标识。
 *
 * 抖音分享出来的文案长这样（整段粘贴很常见）：
 *   7.94 复制打开抖音，看看【某某的作品】xxx https://v.douyin.com/iRxAbCd/ 复制此链接...
 *
 * 所以要处理两种形态：短链（需要跟随 302）和已经带作品 ID 的完整链接，
 * 最后兜底识别裸的 19 位 ID。
 */
object LinkParser {

    sealed interface Target {
        /** v.douyin.com 短链，需要请求一次拿真实地址 */
        data class ShortLink(val url: String) : Target

        /** 已经拿到作品 ID，可直接解析 */
        data class AwemeId(val id: String) : Target
    }

    private val URL_RE = Regex("""https?://[^\s，,、；;）)】」"'<>]+""")
    private val SHORT_RE = Regex("""^https?://v\.douyin\.com/[A-Za-z0-9_\-]+""")
    private val MODAL_RE = Regex("""[?&]modal_id=(\d{15,25})""")
    private val VIDEO_RE = Regex("""/video/(\d{15,25})""")
    private val NOTE_RE = Regex("""/note/(\d{15,25})""")
    private val BARE_ID_RE = Regex("""(?<!\d)(\d{19})(?!\d)""")

    fun parse(raw: String): Target? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        for (url in URL_RE.findAll(text).map { it.value }) {
            SHORT_RE.find(url)?.let { return Target.ShortLink(it.value) }
            MODAL_RE.find(url)?.let { return Target.AwemeId(it.groupValues[1]) }
            VIDEO_RE.find(url)?.let { return Target.AwemeId(it.groupValues[1]) }
            NOTE_RE.find(url)?.let { return Target.AwemeId(it.groupValues[1]) }
        }

        BARE_ID_RE.find(text)?.let { return Target.AwemeId(it.groupValues[1]) }
        return null
    }
}
