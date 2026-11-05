package com.qushuiyin.app.parser

/**
 * 解析失败的分类。
 *
 * 为什么要分类：UI 上「修复」按钮只在 [Schema] 时出现。
 * 如果网络断了也给用户一个「修复」按钮，他点两次没效果之后就再也不点了。
 */
sealed interface ParseError {

    val message: String

    /** 是否值得触发「在线修复」（拉取新规则）——只有页面结构变化才算 */
    val canRepair: Boolean

    /** 是否值得原样重试 / 换身份重试 */
    val canRetry: Boolean

    /** 网络不通、超时 */
    data object Network : ParseError {
        override val message: String = "网络不可用，或请求抖音超时"
        override val canRepair: Boolean = false
        override val canRetry: Boolean = true
    }

    /** 文本里没有抖音链接 */
    data object NoLink : ParseError {
        override val message: String = "没有在这段文字里找到抖音链接"
        override val canRepair: Boolean = false
        override val canRetry: Boolean = false
    }

    /** 403 / 风控 / 需要验证 */
    data object Blocked : ParseError {
        override val message: String = "请求被拦截，试试切换身份重试"
        override val canRepair: Boolean = false
        override val canRetry: Boolean = true
    }

    /** 页面能打开，但取不到预期的数据结构 —— 这才是「版式变了」 */
    data object Schema : ParseError {
        override val message: String = "抖音页面结构有变化，解析规则需要更新"
        override val canRepair: Boolean = true
        override val canRetry: Boolean = true
    }

    /** 作品已删除 / 私密 / 仅好友可见 */
    data object Gone : ParseError {
        override val message: String = "作品不存在，或作者已设为私密"
        override val canRepair: Boolean = false
        override val canRetry: Boolean = false
    }

    data class Unknown(val detail: String) : ParseError {
        override val message: String = "未识别的错误：$detail"
        override val canRepair: Boolean = false
        override val canRetry: Boolean = false
    }
}
