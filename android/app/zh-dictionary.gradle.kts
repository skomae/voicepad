// Chinese–English dictionary for the word breakdown, bundled as an asset so
// it works offline. Built once from two downloads and kept out of git (like
// the sherpa-onnx AAR and the Silero VAD model):
//
// - CC-CEDICT (CC BY-SA 4.0, https://cc-cedict.org): words, readings, meanings.
// - jieba's word list (MIT, https://github.com/fxsjy/jieba): word frequencies,
//   used to split sentences into words and rank meanings.
//
// Output: assets/zh_dict.txt.gz, CC-CEDICT's lines each prefixed with the
// word's jieba frequency and a tab (see ZhDictionary). Being derived from
// CC-CEDICT, the file is under CC BY-SA 4.0 too; its header says so.

import java.net.URI
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

val zhDictionary: File = (extra.properties["zhDictionaryFile"] as? File)
    ?: layout.projectDirectory.file("src/main/assets/zh_dict.txt.gz").asFile

tasks.register("downloadChineseDictionary") {
    description = "Downloads CC-CEDICT and jieba's word frequencies and merges them into the dictionary asset."
    val target = zhDictionary
    outputs.file(target)
    onlyIf { !target.exists() }
    doLast {
        fun fetch(url: String): ByteArray {
            logger.lifecycle("Downloading $url")
            val connection = URI(url).toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            return connection.getInputStream().use { it.readBytes() }
        }
        // MDBG hosts CC-CEDICT; the Zhongwen browser extension keeps a current copy on GitHub.
        val cedict = runCatching {
            GZIPInputStream(fetch("https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz").inputStream())
                .use { it.readBytes() }
        }.recoverCatching {
            logger.warn("MDBG download failed (${it.message}); trying the GitHub copy.")
            fetch("https://raw.githubusercontent.com/cschiller/zhongwen/master/data/cedict_ts.u8")
        }.getOrThrow().toString(Charsets.UTF_8)
        check(cedict.lineSequence().count { !it.startsWith("#") } > 100_000) { "CC-CEDICT download looks incomplete." }

        val jieba = fetch("https://raw.githubusercontent.com/fxsjy/jieba/v0.42.1/jieba/dict.txt").toString(Charsets.UTF_8)
        val frequency = HashMap<String, Int>(400_000)
        jieba.lineSequence().forEach { line ->
            val parts = line.split(' ')
            if (parts.size >= 2) parts[1].toIntOrNull()?.let { frequency[parts[0]] = it }
        }
        check(frequency.size > 100_000) { "jieba word list looks incomplete." }

        target.parentFile.mkdirs()
        val tmp = File(target.path + ".part")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("# VoicePad Chinese dictionary: CC-CEDICT with jieba word frequencies.\n")
            out.write("# CC-CEDICT: https://cc-cedict.org, licensed CC BY-SA 4.0; so is this file.\n")
            out.write("# jieba word frequencies: https://github.com/fxsjy/jieba (MIT).\n")
            cedict.lineSequence().forEach { line ->
                if (line.startsWith("#!")) out.write("$line\n") // version and date
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val simplified = line.substringBefore(" [").split(' ').getOrNull(1) ?: return@forEach
                out.write("${frequency[simplified] ?: 0}\t$line\n")
            }
        }
        tmp.renameTo(target)
    }
}
