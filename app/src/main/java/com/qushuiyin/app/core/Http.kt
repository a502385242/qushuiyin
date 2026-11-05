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
}
