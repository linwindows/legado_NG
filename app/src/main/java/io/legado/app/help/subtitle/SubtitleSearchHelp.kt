package io.legado.app.help.subtitle

import com.google.gson.JsonParser
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

data class SubtitleItem(
    val name: String,
    val url: String,
    val languages: List<String> = emptyList(),
    val ext: String = "",
)

/**
 * 播放页在线搜字幕：迅雷字幕 API。
 * 结果按语言排序，中文（简/繁/中字）优先。
 */
object SubtitleSearchHelp {

    private const val API = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name="

    /** 从视频标题里提取番号，供播放页预填搜索词。 */
    fun extractCode(title: String): String? {
        val text = title.replace("｜", " ").trim()
        if (text.isBlank()) return null
        // FC2：FC2-PPV-1234567 / FC2PPV 1234567
        Regex("(?i)fc2[-_ ]?(?:ppv)?[-_ ]?(\\d{4,10})").find(text)?.let { m ->
            val num = m.groupValues[1]
            return "FC2-PPV-$num"
        }
        // 常规番号：HUBLK-070 / nsfs_528（要求字母与数字之间有分隔符，避免误截普通单词）
        Regex("\\b([A-Za-z]{2,6})[-_ ](\\d{2,5})\\b").find(text)?.let { m ->
            return m.groupValues[1].uppercase() + "-" + m.groupValues[2]
        }
        // 无分隔符贴在一起的形式，仅当其后紧跟分隔或行尾时接受（如 HUBLK070）
        Regex("\\b([A-Za-z]{3,6})(\\d{2,5})\\b(?![A-Za-z0-9])").find(text)?.let { m ->
            return m.groupValues[1].uppercase() + "-" + m.groupValues[2]
        }
        return null
    }

    suspend fun search(code: String): List<SubtitleItem> = withContext(Dispatchers.IO) {
        val body = withTimeout(20_000L) {
            okHttpClient.newCallResponseBody { url(API + java.net.URLEncoder.encode(code, "UTF-8")) }
                .decompressed().string()
        }
        parse(body)
    }

    suspend fun download(item: SubtitleItem): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val body = withTimeout(20_000L) {
            okHttpClient.newCallResponseBody { url(item.url) }.decompressed().string()
        }
        parseSrt(body)
    }

    internal fun parseSrt(raw: String): List<SubtitleCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = text.split(Regex("\\n\\s*\\n"))
        val timing = Regex("(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3})")
        return blocks.mapNotNull { block ->
            val lines = block.lines()
            val index = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (index < 0) return@mapNotNull null
            val match = timing.find(lines[index]) ?: return@mapNotNull null
            val start = parseTime(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseTime(match.groupValues[2]) ?: return@mapNotNull null
            val caption = lines.drop(index + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
            if (caption.isBlank() || end <= start) null else SubtitleCue(start, end, caption)
        }.sortedBy { it.startMs }
    }

    private fun parseTime(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size != 3) return null
        val seconds = parts[2].split('.')
        val fraction = (seconds.getOrNull(1).orEmpty() + "000").take(3).toLongOrNull() ?: 0L
        return (parts[0].toLongOrNull() ?: return null) * 3_600_000L +
                (parts[1].toLongOrNull() ?: return null) * 60_000L +
                (seconds[0].toLongOrNull() ?: return null) * 1000L + fraction
    }

    internal fun parse(body: String): List<SubtitleItem> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("字幕接口返回异常")
        if (root.get("code")?.asInt != 0) throw IllegalStateException("字幕接口返回异常")
        val arr = root.getAsJsonArray("data") ?: return emptyList()
        val items = mutableListOf<SubtitleItem>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val url = obj.get("url")?.asString ?: continue
            if (!url.startsWith("http")) continue
            val langs = obj.getAsJsonArray("languages")?.mapNotNull {
                runCatching { it.asString }.getOrNull()
            }.orEmpty()
                items.add(
                    SubtitleItem(
                        name = obj.get("name")?.asString ?: url.substringAfterLast('/'),
                        url = url,
                        languages = langs,
                        ext = obj.get("ext")?.asString ?: "",
                    )
                )
            }
        // 中文优先，其次语言未知的，最后其它
        return items.sortedByDescending { it.languages.any(::isChineseLang) }
    }

    private fun isChineseLang(lang: String): Boolean {
        val l = lang.lowercase()
        return l.contains("简") || l.contains("繁") || l.contains("中") ||
                l.contains("zh") || l.contains("chinese")
    }
}
