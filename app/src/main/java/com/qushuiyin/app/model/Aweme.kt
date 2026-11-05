package com.qushuiyin.app.model

/**
 * 一次解析的成果。字段都是展示与下载所必需的，不做多余建模。
 */
data class Aweme(
    val id: String,
    val desc: String,
    val authorName: String,
    val coverUrl: String?,
    /** 已按码率从高到低排序，下载时取第一个即可 */
    val videoUrls: List<String>,
    /** 图文作品的图片地址，已尽量还原为原图 */
    val imageUrls: List<String>
) {
    val isImagePost: Boolean get() = imageUrls.isNotEmpty() && videoUrls.isEmpty()
    val bestVideoUrl: String? get() = videoUrls.firstOrNull()

    /** 文件名用：作者_标题前20字_ID，过滤掉文件系统敏感字符 */
    fun suggestedFileName(ext: String = "mp4"): String {
        val safeDesc = desc
            .replace(Regex("""[\\/:*?"<>|\r\n\t#]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(20)
        val safeAuthor = authorName
            .replace(Regex("""[\\/:*?"<>|\r\n\t#]"""), " ")
            .trim()
            .take(12)
        return listOf(safeAuthor, safeDesc, id)
            .filter { it.isNotBlank() }
            .joinToString("_") + ".$ext"
    }
}
