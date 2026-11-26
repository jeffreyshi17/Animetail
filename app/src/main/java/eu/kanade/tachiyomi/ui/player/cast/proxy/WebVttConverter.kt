package eu.kanade.tachiyomi.ui.player.cast.proxy

/**
 * Cast receivers only render WebVTT side-loaded subtitles. Sources mostly ship SRT or ASS, so the
 * proxy converts them on the fly. ASS styling (positions, fonts, karaoke) is dropped; the text and
 * timing are kept.
 */
object WebVttConverter {

    private val srtTiming = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})(.*)""",
    )
    private val assOverride = Regex("""\{[^}]*\}""")

    enum class Format { WEBVTT, SRT, ASS, UNKNOWN }

    fun detect(text: String, nameHint: String? = null): Format {
        val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val name = nameHint?.substringBefore('?')?.lowercase().orEmpty()
        return when {
            head.startsWith("WEBVTT") -> Format.WEBVTT

            head.startsWith("[Script Info]", ignoreCase = true) || name.endsWith(".ass") || name.endsWith(".ssa") ->
                Format.ASS

            srtTiming.containsMatchIn(head.take(500)) -> Format.SRT

            else -> Format.UNKNOWN
        }
    }

    /** Returns WebVTT text; unrecognised input is passed through, the receiver then ignores it. */
    fun toWebVtt(text: String, nameHint: String? = null): String {
        val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return when (detect(clean, nameHint)) {
            Format.WEBVTT, Format.UNKNOWN -> clean
            Format.SRT -> fromSrt(clean)
            Format.ASS -> fromAss(clean)
        }
    }

    private fun fromSrt(srt: String): String {
        val out = StringBuilder("WEBVTT\n\n")
        for (block in srt.trim().split(Regex("\n{2,}"))) {
            val lines = block.lines()
            val timingIndex = lines.indexOfFirst { srtTiming.matches(it.trim()) }
            if (timingIndex < 0) continue
            val match = srtTiming.matchEntire(lines[timingIndex].trim())!!.groupValues
            val start = timestamp(match[1], match[2], match[3], match[4])
            val end = timestamp(match[5], match[6], match[7], match[8])
            val body = lines.drop(timingIndex + 1).filter { it.isNotBlank() }
            if (body.isEmpty()) continue
            out.append(start).append(" --> ").append(end).append('\n')
            body.forEach { out.append(escapeCueText(it)).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    private fun fromAss(ass: String): String {
        var format: List<String>? = null
        var inEvents = false
        val cues = mutableListOf<Triple<Long, Long, String>>()
        for (line in ass.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            when {
                trimmed.startsWith("Format:", ignoreCase = true) ->
                    format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }

                trimmed.startsWith("Dialogue:", ignoreCase = true) -> {
                    val fields = format ?: DEFAULT_ASS_FORMAT
                    // Text is the last field and may itself contain commas.
                    val values = trimmed.substringAfter(':').trimStart().split(',', limit = fields.size)
                    if (values.size < fields.size) continue
                    val start = assTime(values[fields.indexOf("start")]) ?: continue
                    val end = assTime(values[fields.indexOf("end")]) ?: continue
                    val text = values[fields.indexOf("text")]
                        .replace(assOverride, "")
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                        .trim()
                    if (text.isNotEmpty()) cues += Triple(start, end, text)
                }
            }
        }
        val out = StringBuilder("WEBVTT\n\n")
        cues.sortedBy { it.first }.forEach { (start, end, text) ->
            out.append(formatMs(start)).append(" --> ").append(formatMs(end)).append('\n')
            text.lines().forEach { out.append(escapeCueText(it)).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    private val DEFAULT_ASS_FORMAT =
        listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")

    /** ASS times are `H:MM:SS.cc` (centiseconds). */
    private fun assTime(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return (hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000)
    }

    private fun timestamp(h: String, m: String, s: String, ms: String): String =
        formatMs((h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + ms.padEnd(3, '0').toLong())

    private fun formatMs(totalMs: Long): String {
        val hours = totalMs / 3_600_000
        val minutes = totalMs / 60_000 % 60
        val seconds = totalMs / 1000 % 60
        val millis = totalMs % 1000
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    /** `-->` and stray `&`/`<` would break cue parsing; SRT's basic `<i>`/`<b>` tags are valid VTT. */
    private fun escapeCueText(line: String): String =
        line.replace("-->", "->").replace(Regex("&(?![a-zA-Z]+;|#\\d+;)"), "&amp;")
}
