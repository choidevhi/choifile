package com.choidev.choifile.ebook.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 텍본의 인코딩을 판별해 문자열로 바꾼다. 한국 텍본은 UTF-8 아니면 CP949(EUC-KR 확장)가 대부분이다. */
object TextDecoder {

    data class Decoded(val text: String, val encoding: String)

    fun decode(bytes: ByteArray): Decoded {
        val bom = detectBom(bytes)
        if (bom != null) {
            val (charset, skip) = bom
            val text = String(bytes, skip, bytes.size - skip, charset)
            return Decoded(normalize(text), charset.name())
        }

        val utf8 = tryStrict(bytes, Charsets.UTF_8)
        if (utf8 != null) return Decoded(normalize(utf8), "UTF-8")

        // UTF-8로 안 풀리면 한국어 윈도우 인코딩으로 본다. 기기마다 등록된 이름이 달라서 차례로 찾는다.
        val korean = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR")
            .firstOrNull { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
        val charset = korean?.let { Charset.forName(it) } ?: Charsets.ISO_8859_1
        return Decoded(normalize(String(bytes, charset)), charset.name())
    }

    private fun detectBom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun tryStrict(bytes: ByteArray, charset: Charset): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    // 줄바꿈을 \n 하나로 맞추고, 쪽 나눔을 흐트러뜨리는 제어 문자(탭 제외)를 없앤다.
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> {
                    sb.append('\n')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }

                c == '\t' -> sb.append("    ")
                c.code == 0xFEFF -> Unit
                c < ' ' && c != '\n' -> Unit
                else -> sb.append(c)
            }
            i++
        }

        return sb.toString()
    }
}
