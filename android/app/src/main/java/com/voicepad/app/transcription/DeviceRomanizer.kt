package com.voicepad.app.transcription

import android.icu.text.Transliterator
import android.os.Build

/**
 * The [Romanizer] backed by Android's built-in ICU (Android 10 and later;
 * earlier versions get no romanisation). Shared by translation, the notes
 * list's pinyin line and the word breakdown.
 */
object DeviceRomanizer {
    private val cache = HashMap<String, Transliterator?>()

    /** ICU transform [id] applied to [text], or null when unavailable. */
    @Synchronized
    fun transliterate(id: String, text: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val t = cache.getOrPut(id) { runCatching { Transliterator.getInstance(id) }.getOrNull() } ?: return null
        return runCatching { t.transliterate(text) }.getOrNull()
    }

    val romanizer = Romanizer(::transliterate)

    /** Pinyin for [text] if it reads as Mandarin (Chinese characters, no Japanese kana), else null. */
    fun mandarinPinyin(text: String, language: String? = null): String? {
        if (language == "ja" || language == "yue" || language == "ko") return null
        if (!LanguageTags.containsHan(text) || LanguageTags.containsKana(text)) return null
        return romanizer.pinyin(text)
    }

    /** The most common reading of one character, with tone marks ("le" for 了). */
    fun usualReading(char: String): String? = transliterate("Han-Latin", char)?.trim()?.takeIf { it.isNotEmpty() }
}
