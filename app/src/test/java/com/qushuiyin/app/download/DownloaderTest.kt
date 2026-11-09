package com.qushuiyin.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只测纯 JVM 能跑的部分（不碰 MediaStore / Context）。
 *
 * 这两个函数看着琐碎，但错了的后果都很具体：扩展名错 → 相册里图片打不开；
 * 体积格式错 → 用户在「9.7MB」和「9.7MB」之间根本看不出文件是不是完整的。
 */
class DownloaderTest {

    @Test
    fun `图片扩展名按 URL 判断，问号和参数不影响`() {
        assertEquals("jpg", Downloader.guessImageExt("https://p3.douyinpic.com/a/b.jpeg?x=1"))
        assertEquals("png", Downloader.guessImageExt("https://p3.douyinpic.com/a/b.PNG"))
        assertEquals("webp", Downloader.guessImageExt("https://p3.douyinpic.com/a/b.webp~tplv-dy.webp"))
        assertEquals("gif", Downloader.guessImageExt("https://p3.douyinpic.com/a/b.gif"))
    }

    @Test
    fun `没有扩展名时退回 jpg`() {
        assertEquals("jpg", Downloader.guessImageExt("https://p3.douyinpic.com/obj/abc123"))
        assertEquals("jpg", Downloader.guessImageExt(""))
    }

    @Test
    fun `体积文案能表达从字节到 MB`() {
        assertEquals("2B", Downloader.humanSize(2))
        assertEquals("1KB", Downloader.humanSize(1024))
        assertEquals("9.7MB", Downloader.humanSize(10_120_457))
    }
}
