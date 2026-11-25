package com.voicepad.app.zh

import android.content.Context
import com.voicepad.app.transcription.DeviceRomanizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

/**
 * The bundled dictionary (assets/zh_dict.dat, see app/build.gradle.kts), loaded
 * the first time a word breakdown is opened and kept for the rest of the app's
 * life (about 40 MB).
 *
 * The asset is gzipped but named .dat on purpose: the asset merger gunzips *.gz
 * assets and ships them without the suffix, which made the dictionary look absent.
 */
object ChineseDictionary {
    private const val ASSET = "zh_dict.dat"
    private val lock = Mutex()
    @Volatile private var loaded: WordBreakdown? = null

    /** The breakdown engine, or null if the dictionary isn't in this build. */
    suspend fun get(context: Context): WordBreakdown? = loaded ?: lock.withLock {
        loaded ?: withContext(Dispatchers.IO) {
            runCatching {
                context.applicationContext.assets.open(ASSET).use { GZIPInputStream(it.buffered(1 shl 16)).readBytes() }
            }.getOrNull()?.let { bytes ->
                WordBreakdown(
                    ZhDictionary.parse(bytes),
                    DeviceRomanizer::usualReading,
                    // Segments with HanLP; if its data can't load (a shrunken
                    // build), WordBreakdown falls back to the dictionary itself.
                    runCatching { HanlpSegmenter() }.getOrNull(),
                )
            }
        }.also { loaded = it }
    }
}
