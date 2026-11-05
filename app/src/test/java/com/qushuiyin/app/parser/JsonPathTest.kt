package com.qushuiyin.app.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPathTest {

    private val json = Json.parseToJsonElement(
        """
        {
          "loaderData": {
            "video_(id)/page": {
              "videoInfoRes": {
                "item_list": [
                  { "aweme_id": "7300000000000000001", "desc": "第一个" }
                ]
              }
            }
          },
          "video": {
            "bit_rate": [
              { "bit_rate": 1000, "play_addr": { "url_list": ["low"] } },
              { "bit_rate": 5000, "play_addr": { "url_list": ["high"] } }
            ],
            "play_addr": { "url_list": ["default"] }
          },
          "images": [
            { "url_list": ["img1"] },
            { "url_list": ["img2"] }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `通配符能穿过带作品 ID 的键名`() {
        val id = JsonPath.firstString(json, "loaderData.*.videoInfoRes.item_list[0].aweme_id")
        assertEquals("7300000000000000001", id)
    }

    @Test
    fun `数组展开加下标能取出多个地址`() {
        val urls = JsonPath.query(json, "video.bit_rate[].play_addr.url_list[0]")
        assertEquals(2, urls.size)
        assertEquals("low", (urls[0] as JsonPrimitive).content)
        assertEquals("high", (urls[1] as JsonPrimitive).content)
    }

    @Test
    fun `纯数字 token 当作数组下标`() {
        val v = JsonPath.firstString(json, "images.1.url_list.0")
        assertEquals("img2", v)
    }

    @Test
    fun `路径不存在时返回空而不是抛异常`() {
        assertTrue(JsonPath.query(json, "a.b.c[0]").isEmpty())
        assertEquals(null, JsonPath.firstString(json, "loaderData.not_exist.x"))
    }
}
