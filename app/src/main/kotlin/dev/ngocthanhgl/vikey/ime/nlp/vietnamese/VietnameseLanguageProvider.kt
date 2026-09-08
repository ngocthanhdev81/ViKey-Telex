package dev.ngocthanhgl.vikey.ime.nlp.vietnamese

import android.content.Context
import dev.ngocthanhgl.vikey.appContext
import dev.ngocthanhgl.vikey.ime.core.Subtype
import dev.ngocthanhgl.vikey.ime.editor.EditorContent
import dev.ngocthanhgl.vikey.ime.nlp.SpellingProvider
import dev.ngocthanhgl.vikey.ime.nlp.SpellingResult
import dev.ngocthanhgl.vikey.ime.nlp.SuggestionCandidate
import dev.ngocthanhgl.vikey.ime.nlp.SuggestionProvider
import dev.ngocthanhgl.vikey.ime.nlp.WordSuggestionCandidate
import dev.ngocthanhgl.vikey.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

class VietnameseLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.vietnamese"

        private const val PREFIX_INDEX_MAX_LENGTH = 6
        private const val PERSONAL_DATA_FILE = "vietnamese_user_data.json"
        private const val BIGRAM_MAX_ENTRIES = 4096
        private const val PERSONAL_BOOST_WEIGHT = 0.5

        // Geometric-rank prior for glide rerank fusion (see
        // rerankGlideSuggestions): bigram evidence lives in [0, 1], so a
        // prior of 1.0 keeps geometry primary while letting strong context
        // promote within the top ranks.
        private const val GEO_PRIOR = 1.0
        private const val BIGRAM_SMOOTHING_K = 6.0

        /**
         * Fold a Vietnamese word to its toneless ASCII skeleton so toneless input
         * ("duoc") can match dictionary forms carrying diacritics ("được").
         *
         * NFD splits every precomposed Vietnamese glyph into base letter + combining
         * mark; all such marks live in U+0300..U+036F (including U+031B, the horn of
         * ơ/ư). Đ (U+0111) has no canonical decomposition, so it is mapped manually.
         */
        fun foldVietnamese(word: String): String {
            val normalized = Normalizer.normalize(word, Normalizer.Form.NFD)
            val sb = StringBuilder(normalized.length)
            for (c in normalized) {
                when {
                    c == 'đ' -> sb.append('d')
                    c == 'Đ' -> sb.append('D')
                    c in '\u0300'..'\u036F' -> {}
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private data class DictEntry(val word: String, val freq: Int)

        private data class ScoredWord(val word: String, val corpusFreq: Int, val personalCount: Int) {
            fun blendedScore(maxFreq: Long): Double {
                val normCorpus = if (maxFreq > 0) ln(1.0 + corpusFreq) / ln(1.0 + maxFreq) else 0.0
                val normPersonal = (personalCount.coerceAtMost(25)) / 25.0
                return normCorpus + PERSONAL_BOOST_WEIGHT * normPersonal
            }
        }
    }

    private val appContext by context.appContext()

    // All dictionary/index maps are guarded together by dictLock (plain monitor locks,
    // so they are also safe to touch from non-suspend helpers).
    private val dictLock = Any()

    private val wordData = mutableMapOf<String, Int>()
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    /** prefix (1..6 chars, lowercase) -> entries sorted by frequency descending. */
    private val prefixIndex = mutableMapOf<String, MutableList<DictEntry>>()

    /** folded lowercase skeleton -> real dictionary words sorted by frequency descending. */
    private val foldedIndex = mutableMapOf<String, MutableList<String>>()

    /** lowercase -> original dictionary casing, so suggestions restore proper capitalization. */
    private val lowerToOriginal = mutableMapOf<String, String>()

    @Volatile
    private var maxFreq = 1L

    // ---- On-device learning state ----

    private val personalWords = LinkedHashMap<String, Int>()
    /** key = "prev|next" on folded lowercase forms; insertion-ordered for eviction. */
    private val bigramCounts = LinkedHashMap<String, Int>()
    @Volatile
    private var userDataDirty = false
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val providerId = ProviderId

    override suspend fun create() {
        withContext(Dispatchers.IO) {
            loadPersonalData()
        }
        startPeriodicSave()
    }

    override suspend fun preload(subtype: Subtype) {
    }

    private suspend fun loadDict() {
        val shouldLoad = synchronized(dictLock) { wordData.isEmpty() }
        if (!shouldLoad) return
        try {
            val rawData = withContext(Dispatchers.IO) {
                appContext.assets.readText("ime/dict/vi.json")
            }
            val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
            synchronized(dictLock) {
                if (wordData.isEmpty()) {
                    wordData.putAll(jsonData)
                    rebuildIndexesLocked(jsonData)
                }
            }
        } catch (e: Exception) {
            flogDebug { "Failed to load Vietnamese dictionary: ${e.message}" }
        }
    }

    /** Caller must hold [dictLock]. */
    private fun rebuildIndexesLocked(dict: Map<String, Int>) {
        var max = 1L
        prefixIndex.clear()
        for ((word, freq) in dict) {
            if (freq > max) max = freq.toLong()
            val lower = word.lowercase(Locale.ROOT)
            val upperLen = minOf(PREFIX_INDEX_MAX_LENGTH, lower.length)
            for (len in 1..upperLen) {
                prefixIndex.getOrPut(lower.take(len)) { mutableListOf() }.add(DictEntry(word, freq))
            }
        }
        for (list in prefixIndex.values) {
            list.sortByDescending { it.freq }
        }
        foldedIndex.clear()
        for ((word, _) in dict) {
            val folded = foldVietnamese(word).lowercase(Locale.ROOT)
            if (folded.isEmpty()) continue
            foldedIndex.getOrPut(folded) { mutableListOf() }.add(word)
        }
        // Sort every bucket by its corpus frequency (descending).
        for (list in foldedIndex.values) {
            list.sortByDescending { dict[it] ?: 0 }
        }
        lowerToOriginal.clear()
        for (word in dict.keys) {
            lowerToOriginal.putIfAbsent(word.lowercase(Locale.ROOT), word)
        }
        maxFreq = max
    }

    // ---- Learning API ----

    /**
     * Records a word the user actually typed or accepted, growing the personal
     * dictionary that boosts future suggestions.
     */
    fun recordWord(raw: String) {
        val lc = raw.lowercase(Locale.ROOT).trimEnd(',', '.', '?', '!', ';', ':', '"', '\'', ')', ']', '}', '>')
        if (lc.length < 2 || lc.any { !it.isLetter() }) return
        synchronized(personalWords) {
            personalWords[lc] = (personalWords[lc] ?: 0) + 1
        }
        userDataDirty = true
    }

    /**
     * Records a bigram observation so subsequent suggestions can favor words that
     * naturally follow the preceding one. Keys are folded to be diacritic-insensitive.
     */
    fun recordBigram(prevWord: String, nextWord: String) {
        val prev = foldVietnamese(prevWord.trim()).lowercase(Locale.ROOT)
        val next = foldVietnamese(nextWord.trim()).lowercase(Locale.ROOT)
        if (prev.isEmpty() || next.isEmpty()) return
        if (prev.any { !it.isLetter() } || next.any { !it.isLetter() }) return
        synchronized(bigramCounts) {
            val key = "$prev|$next"
            bigramCounts[key] = (bigramCounts[key] ?: 0) + 1
            while (bigramCounts.size > BIGRAM_MAX_ENTRIES) {
                val eldest = bigramCounts.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        userDataDirty = true
    }

    override suspend fun getBigramFrequencyFor(prevWord: String, nextWord: String): Double {
        if (prevWord.isBlank() || nextWord.isBlank()) return 0.0
        val prev = foldVietnamese(prevWord.trim()).lowercase(Locale.ROOT)
        val next = foldVietnamese(nextWord.trim()).lowercase(Locale.ROOT)
        val count = synchronized(bigramCounts) { bigramCounts["$prev|$next"] ?: 0 }
        return count / (count + BIGRAM_SMOOTHING_K)
    }

    private fun startPeriodicSave() {
        bgScope.launch {
            while (isActive) {
                delay(30_000)
                if (userDataDirty) savePersonalData()
            }
        }
    }

    private suspend fun loadPersonalData() {
        try {
            val f = File(appContext.filesDir, PERSONAL_DATA_FILE)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val words = root.optJSONObject("words") ?: JSONObject()
            for (key in words.keys()) {
                personalWords[key] = words.optInt(key, 1)
            }
            val bigrams = root.optJSONObject("bigrams") ?: JSONObject()
            for (key in bigrams.keys()) {
                bigramCounts[key] = bigrams.optInt(key, 1)
            }
        } catch (e: Exception) {
            flogDebug { "Failed to load Vietnamese user data: ${e.message}" }
        }
    }

    private fun savePersonalData() {
        if (!userDataDirty) return
        try {
            val root = JSONObject()
            val words = JSONObject()
            synchronized(personalWords) {
                for ((word, count) in personalWords) words.put(word, count)
            }
            val bigrams = JSONObject()
            synchronized(bigramCounts) {
                for ((key, count) in bigramCounts) bigrams.put(key, count)
            }
            root.put("words", words)
            root.put("bigrams", bigrams)
            File(appContext.filesDir, PERSONAL_DATA_FILE).writeText(root.toString())
            userDataDirty = false
        } catch (e: Exception) {
            flogDebug { "Failed to save Vietnamese user data: ${e.message}" }
        }
    }

    // ---- Suggestions ----

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        return SpellingResult.validWord()
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val prefix = getCurrentWord(content)
            ?: return emptyList()

        loadDict()

        val lowerPrefix = prefix.lowercase(Locale.ROOT)

        // Pool corpus hits from the prebuilt index...
        val direct = synchronized(dictLock) {
            prefixIndex[lowerPrefix].orEmpty().map { it.word to it.freq }
        }
        val pool = LinkedHashMap<String, Int>(direct.size + 8)
        for ((word, freq) in direct) {
            pool.putIfAbsent(word.lowercase(Locale.ROOT), freq)
        }

        // ...merge personal-dictionary hits (they may not exist in the corpus at all).
        val personalSnapshot = synchronized(personalWords) { personalWords.toMap() }
        for ((word, count) in personalSnapshot) {
            if (!word.startsWith(lowerPrefix)) continue
            val corpusFreq = synchronized(dictLock) { wordData[word] ?: 0 }
            pool[word] = maxOf(pool[word] ?: 0, corpusFreq)
        }

        // Fallback path: match through the toneless folded skeleton so typing
        // "duoc" can surface "được" even without an ASCII dictionary entry.
        val foldedPrefix = foldVietnamese(lowerPrefix)
        if (pool.size < maxCandidateCount && foldedPrefix.isNotEmpty()) {
            val foldedHits = synchronized(dictLock) {
                val out = mutableListOf<String>()
                for ((skeleton, words) in foldedIndex) {
                    if (!skeleton.startsWith(foldedPrefix)) continue
                    for (word in words) {
                        if (pool.putIfAbsent(word.lowercase(Locale.ROOT), 0) == null) {
                            out.add(word)
                        }
                    }
                }
                out
            }
            for (word in foldedHits) {
                val freq = synchronized(dictLock) { wordData[word] ?: 0 }
                if (freq > 0) pool[word.lowercase(Locale.ROOT)] = freq
            }
        }

        if (pool.isEmpty()) return emptyList()

        // Rank with the personal-count boost layered over corpus frequency.
        val scored = ArrayList<ScoredWord>(pool.size)
        for ((lowerWord, corpusFreq) in pool) {
            scored.add(ScoredWord(lowerWord, corpusFreq, personalSnapshot[lowerWord] ?: 0))
        }
        scored.sortByDescending { it.blendedScore(maxFreq) }

        return scored.take(maxCandidateCount).mapIndexed { index, entry ->
            buildCandidate(prefix, entry.word, entry, index, maxCandidateCount)
        }
    }

    private fun buildCandidate(
        prefix: String,
        lowerWord: String,
        entry: ScoredWord,
        index: Int,
        maxCandidateCount: Int,
    ): SuggestionCandidate {
        val restored = restoreDictionaryCasing(lowerWord)
        // Only treat as exact (auto-commit eligible) when the typed prefix matches the
        // dictionary entry character-for-character, including letter case.
        val isExact = restored == prefix
        val normScore = (entry.blendedScore(maxFreq) / (1.0 + PERSONAL_BOOST_WEIGHT)).coerceIn(0.0, 1.0)
        val confidence = if (isExact) {
            1.0
        } else {
            (normScore * (1.0 - index.toDouble() / maxCandidateCount)).coerceIn(0.05, 0.99)
        }
        return WordSuggestionCandidate(
            text = applyCasePattern(prefix, restored),
            confidence = confidence,
            isEligibleForAutoCommit = isExact && index == 0,
            sourceProvider = this,
        )
    }

    /** Personal entries are stored lowercase; prefer the corpus's original casing when known. */
    private fun restoreDictionaryCasing(lowerWord: String): String {
        val original = synchronized(dictLock) { lowerToOriginal[lowerWord] }
        return original ?: lowerWord
    }

    override suspend fun rerankGlideSuggestions(
        subtype: Subtype,
        textBefore: String,
        candidates: List<String>,
    ): List<String> {
        if (candidates.size < 2) return candidates
        val prevWord = textBefore.substringAfterLast(' ').trim().trimEnd(',', '.', '?', '!', ';', ':')
        // Fuse context with geometry: candidates arrive geometrically ordered
        // (best first). A pure bigram sort would let a context-lucky poor
        // shape beat the true trail, so geometry stays primary via a
        // decaying prior — strong bigram evidence (up to +1.0) can still
        // promote a candidate from the top ranks, but never resurrect a
        // geometrically hopeless one.
        return candidates.mapIndexed { index, candidate ->
            candidate to (getBigramFrequencyFor(prevWord, candidate) + GEO_PRIOR / (index + 1))
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun applyCasePattern(typed: String, word: String): String {
        if (typed.isEmpty()) return word
        val sb = StringBuilder(word)
        var i = 0
        while (i < typed.length && i < sb.length) {
            if (typed[i].isUpperCase()) {
                sb[i] = sb[i].uppercaseChar()
            }
            i++
        }
        return sb.toString()
    }

    private fun getCurrentWord(content: EditorContent): String? {
        content.composingText.let { if (it.isNotBlank()) return it.toString() }
        content.currentWordText.let { if (it.isNotBlank()) return it.toString() }

        val textBefore = content.textBeforeSelection
        if (textBefore.isNotBlank()) {
            val words = textBefore.split(Regex("[\\s\\p{Punct}]+"))
            return words.lastOrNull { it.isNotBlank() }
        }

        return null
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        recordWord(candidate.text.toString())
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return synchronized(dictLock) { wordData.keys.toList() } +
            synchronized(personalWords) { personalWords.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        // Log-scaled normalization keeps this meaningful across the corpus's huge
        // dynamic range (raw counts span single digits to tens of millions); callers
        // receive a value in [0, 1] instead of the old saturating count/255 formula.
        val lc = word.lowercase(Locale.ROOT)
        val count = synchronized(dictLock) { wordData[lc] ?: wordData[word] ?: 0 }
        val base = ln(1.0 + count) / ln(1.0 + maxFreq)
        val personal = synchronized(personalWords) { personalWords[lc] ?: 0 }
        val boosted = base + 0.35 * (personal.coerceAtMost(20) / 20.0)
        return boosted.coerceIn(0.0, 1.0)
    }

    override suspend fun destroy() {
        if (userDataDirty) savePersonalData()
        bgScope.cancel()
    }
}
