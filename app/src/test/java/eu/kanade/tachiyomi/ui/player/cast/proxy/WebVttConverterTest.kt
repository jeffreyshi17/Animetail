package eu.kanade.tachiyomi.ui.player.cast.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebVttConverterTest {

    @Test
    fun `srt becomes webvtt with dotted milliseconds and keeps basic tags`() {
        val srt = "\uFEFF1\r\n00:00:01,500 --> 00:00:03,000\r\n<i>Hello</i>\r\nworld & co\r\n\r\n" +
            "2\r\n01:02:03,04 --> 01:02:05,000\r\nSecond\r\n"

        assertEquals(
            "WEBVTT\n\n" +
                "00:00:01.500 --> 00:00:03.000\n<i>Hello</i>\nworld &amp; co\n\n" +
                "01:02:03.040 --> 01:02:05.000\nSecond\n\n",
            WebVttConverter.toWebVtt(srt),
        )
    }

    @Test
    fun `ass dialogue keeps text with commas and line breaks, drops override tags and sorts by start`() {
        val ass = """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.00,0:00:06.50,Default,,0,0,0,,{\an8}Later
            Dialogue: 0,0:00:01.25,0:00:02.00,Default,,0,0,0,,{\i1}Wait,{\i0} what?\Nreally
            Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,not shown
        """.trimIndent()

        assertEquals(
            "WEBVTT\n\n" +
                "00:00:01.250 --> 00:00:02.000\nWait, what?\nreally\n\n" +
                "00:00:05.000 --> 00:00:06.500\nLater\n\n",
            WebVttConverter.toWebVtt(ass),
        )
    }

    @Test
    fun `webvtt passes through unchanged`() {
        val vtt = "WEBVTT\n\n00:00.000 --> 00:01.000\nhi\n"

        assertEquals(vtt, WebVttConverter.toWebVtt(vtt, "https://h/subs.vtt"))
    }
}
