package com.voicepad.app.transcription

import android.icu.text.Transliterator
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation to English with Google ML Kit, plus romanisation.
 * Each language pair's model (~30 MB) downloads the first time it's needed;
 * after that, translation works offline.
 */
class EnglishTranslator {
    data class Result(val language: String?, val english: String?, val romanization: String?)

    private val languageId = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val romanizer = Romanizer { id, text ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance(id).transliterate(text) }.getOrNull()
        } else {
            null // Android's ICU transliterator arrived in Android 10.
        }
    }

    /**
     * Translates [text] to English. [modelLanguage] is what the speech model
     * reported, if anything. Returns language null/"en" with no English when
     * no translation is needed. Throws if translation was needed but failed.
     */
    suspend fun translate(text: String, modelLanguage: String?): Result {
        val guess = runCatching { languageId.identifyLanguage(text).await() }.getOrNull()
        val language = LanguageTags.decide(modelLanguage, guess, text)
        if (language == null || LanguageTags.isEnglish(language)) return Result(language, null, null)

        val romanization = romanizer.romanize(language, text)
        // ML Kit translates written Chinese; Cantonese text is written the same way.
        val source = TranslateLanguage.fromLanguageTag(if (language == "yue") "zh" else language)
            ?: throw UnsupportedOperationException("Translation from ${LanguageTags.displayName(language)} isn't supported on-device.")
        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            )
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        val english = translator.translate(text).await()
        return Result(language, english, romanization)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
