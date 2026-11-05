package com.qushuiyin.app.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 解析规则。这份结构同时用于：
 *   1. 内置默认值（首次安装就能用）
 *   2. 远程下发的 manifest（用户点「修复」时拉取）
 *
 * 设计要点：所有「取数据的位置」都是**候选列表**而不是单一值。
 * 抖音改版多数情况只是字段平移一层或换了包装键，候选列表能吃掉大部分变更，
 * 剩下的才需要发新版 APK。
 */
@Serializable
data class ParseRules(
    val schemaVersion: Int = 1,
    /** 低于这个版本号的 App 不要应用该规则（规则里可能用了新字段） */
    val minAppVersion: Int = 1,
    val shareHosts: List<String> = DEFAULT_HOSTS,
    val ua: String = DEFAULT_UA,
    /** 从根 JSON 到「单个作品对象」的候选路径 */
    val itemPaths: List<String> = DEFAULT_ITEM_PATHS,
    /** 从作品对象到视频地址的候选路径（不含 bit_rate 特殊处理，那个在代码里做） */
    val videoUrlPaths: List<String> = DEFAULT_VIDEO_PATHS,
    /** 从作品对象到图片地址的候选路径 */
    val imageUrlPaths: List<String> = DEFAULT_IMAGE_PATHS,
    /** 远程配置的签名（内置默认值为空，无需校验） */
    val signature: String = ""
) {
    companion object {
        val DEFAULT_HOSTS = listOf("www.iesdouyin.com", "www.douyin.com")

        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * loaderData 的键名里带作品 ID（形如 video_(id)/page），所以第一段之后必须用 * 通配。
         */
        val DEFAULT_ITEM_PATHS = listOf(
            "loaderData.*.videoInfoRes.item_list[0]",
            "loaderData.*.videoInfoRes.item_list.0",
            "videoInfoRes.item_list[0]",
            "app.videoDetail.itemInfo.itemStruct",
            "aweme_detail"
        )

        val DEFAULT_VIDEO_PATHS = listOf(
            "video.play_addr.url_list[0]",
            "video.download_addr.url_list[0]",
            "video.playApi"
        )

        val DEFAULT_IMAGE_PATHS = listOf(
            "images[].download_url_list[0]",
            "images[].url_list[0]"
        )
    }
}
