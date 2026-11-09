package com.qushuiyin.app.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    /**
     * 解析用客户端：短超时、自动跟随重定向（短链要用到）。
     * 下载另行配置，需要更长的读超时。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 下载用客户端：读超时放长，允许大文件慢速传输 */
    val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 探测用客户端：**必须有总时限**。
     *
     * 真机日志（v0.4.1）实测：一次只取 2 字节的 `Range` 请求在手机上花了 23 秒才回来。
     * 原因是 `retryOnConnectionFailure` 会让 OkHttp 在首条路由（常见是走不通的 IPv6）
     * 上各等一个 connectTimeout，再换下一条重试，时间就叠成了一整个解析超时。
     * `callTimeout` 是唯一能硬性截断整次调用的开关（connect + 排队 + 重定向 + 读全算）。
     */
    val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
