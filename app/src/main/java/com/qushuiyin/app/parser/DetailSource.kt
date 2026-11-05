package com.qushuiyin.app.parser

/**
 * 作品详情的取数来源。
 *
 * 抽成接口是因为抖音换过一次取数方式：2026 年之前分享页 HTML 里直接内嵌 `_ROUTER_DATA`，
 * 一个 GET 就能拿到；现在那个入口只剩一个 1.4KB 的空壳，数据要靠页面自己的 JS
 * 带着 `a_bogus` 签名去请求 `aweme/detail`。哪天它又变回去，也只需要换一个实现。
 */
interface DetailSource {
    /** 返回详情 JSON 原文；失败返回 null */
    suspend fun fetch(awemeId: String): String?
}
