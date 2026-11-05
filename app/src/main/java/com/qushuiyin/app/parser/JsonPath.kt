package com.qushuiyin.app.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 极简 JSON 路径解析器，只覆盖本项目需要的能力：
 *
 *   a.b.c        取字段
 *   a.*.c        对象任意键（通配一层）—— 抖音的 loaderData 键名带作品 ID，必须靠通配
 *   a[0].b       数组下标
 *   a[].b        数组展开（对每个元素继续往下走）
 *
 * 为什么不用 JSONPath 库：只需要这么点能力，自己写 40 行，省一个依赖。
 * 取不到时返回空列表而不是抛异常，调用方按「候选路径」依次尝试即可。
 */
object JsonPath {

    fun query(root: JsonElement, path: String): List<JsonElement> {
        if (path.isBlank()) return emptyList()
        var current: List<JsonElement> = listOf(root)
        for (token in path.split('.').filter { it.isNotBlank() }) {
            if (current.isEmpty()) return emptyList()
            current = current.flatMap { step(it, token) }
        }
        return current.filterNot { it is JsonNull }
    }

    fun first(root: JsonElement, path: String): JsonElement? = query(root, path).firstOrNull()

    fun firstString(root: JsonElement, path: String): String? =
        (first(root, path) as? JsonPrimitive)?.contentOrNull

    private fun step(node: JsonElement, token: String): List<JsonElement> {
        var name = token
        var expand = false
        var index: Int? = null

        val bracket = token.indexOf('[')
        if (bracket >= 0 && token.endsWith("]")) {
            name = token.substring(0, bracket)
            val inner = token.substring(bracket + 1, token.length - 1).trim()
            if (inner.isEmpty()) expand = true else index = inner.toIntOrNull()
        }

        // 通配：展开对象的所有值；纯数字 token 视为数组下标（兼容 a.0.b 写法）；
        // 普通字段：按键取值
        val base: List<JsonElement> = if (name == "*") {
            (node as? JsonObject)?.values?.toList() ?: emptyList()
        } else if (name.isNotEmpty() && name.all { it.isDigit() }) {
            listOfNotNull((node as? JsonArray)?.getOrNull(name.toInt()))
        } else {
            val v = if (name.isEmpty()) node else (node as? JsonObject)?.get(name)
            listOfNotNull(v)
        }

        return when {
            expand -> base.flatMap { (it as? JsonArray)?.toList() ?: listOf(it) }
            index != null -> base.mapNotNull { (it as? JsonArray)?.getOrNull(index) }
            else -> base
        }
    }
}
