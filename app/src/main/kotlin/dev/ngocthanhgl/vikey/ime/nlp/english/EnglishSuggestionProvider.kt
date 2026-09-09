package dev.ngocthanhgl.vikey.ime.nlp.english

import android.content.Context
import dev.ngocthanhgl.vikey.ime.core.Subtype
import dev.ngocthanhgl.vikey.ime.editor.EditorContent
import dev.ngocthanhgl.vikey.ime.nlp.SuggestionCandidate
import dev.ngocthanhgl.vikey.ime.nlp.SuggestionProvider
import dev.ngocthanhgl.vikey.ime.nlp.WordSuggestionCandidate
import dev.ngocthanhgl.vikey.ime.nlp.isWordBoundary
import dev.ngocthanhgl.vikey.ime.nlp.ngramHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class EnglishSuggestionProvider(private val context: Context) : SuggestionProvider {
    companion object {
        const val ProviderId = "org.florisboard.nlp.providers.english"
        private const val EN_WORDS = "ime/dict/en.json"
        private const val EN_NGRAMS = "ime/dict/en_ngrams.json"
        private const val PERSONAL_DICT = "english_personal_dict.json"
        private const val BIGRAM_MAX_ENTRIES = 4096
        private const val TRIGRAM_MAX_ENTRIES = 4096

        /**
         * Stupid-backoff penalty per backed-off level (same as the Vietnamese
         * provider so both languages rank on one scale).
         */
        private const val BACKOFF_WEIGHT = 0.4

        /**
         * How much one personal observation counts against static corpus
         * counts (same scale as the Vietnamese provider).
         */
        private const val USER_OBS_WEIGHT = 8.0

        /**
         * Geometric-rank prior for glide rerank fusion (mirrors the
         * Vietnamese provider: geometry primary, strong context promotes).
         */
        private const val GEO_PRIOR = 1.0
    }

    private val wordFrequencies = mutableMapOf<String, Int>()
    private val sortedWords = mutableListOf<String>()
    private val prefixMap = mutableMapOf<String, MutableList<String>>()
    private val personalDict = mutableMapOf<String, Int>()
    /** key = "prev|next" lowercase; insertion-ordered for eviction. */
    private val bigramCounts = LinkedHashMap<String, Int>()
    /** key = "w1|w2|w3" lowercase; insertion-ordered for eviction. */
    private val trigramCounts = LinkedHashMap<String, Int>()

    /** Static corpus N-grams shipped in the APK (en_ngrams.json). */
    private val staticBigrams = HashMap<String, Int>()
    private val staticTrigrams = HashMap<String, Int>()
    private val staticBiTotals = HashMap<String, Int>()
    private val staticTriTotals = HashMap<String, Int>()
    private var maxFreq = 1L
    private var personalDirty = false
    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val providerId = ProviderId

    override suspend fun create() {
        withContext(Dispatchers.IO) {
            loadWords()
            loadPersonalDict()
        }
        startPeriodicSave()
    }

    private fun startPeriodicSave() {
        bgScope.launch {
            while (isActive) {
                delay(30_000)
                if (personalDirty) savePersonalDict()
            }
        }
    }

    private fun loadWords() {
        try {
            val raw = context.assets.open(EN_WORDS).bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            var max = 1L
            for (key in json.keys()) {
                val w = key.lowercase()
                if (w.isNotEmpty() && w.none { it.isWhitespace() } && w.all { it.isLetter() || it == '\'' }) {
                    val freq = json.getInt(key)
                    wordFrequencies[w] = freq
                    if (freq > max) max = freq.toLong()
                }
            }
            maxFreq = max
            sortedWords.addAll(wordFrequencies.entries.sortedByDescending { it.value }.map { it.key })
            for (word in sortedWords) {
                for (i in 1..word.length.coerceAtMost(6)) {
                    prefixMap.getOrPut(word.take(i)) { mutableListOf() }.add(word)
                }
            }
        } catch (_: Exception) {}
        try {
            val raw = context.assets.open(EN_NGRAMS).bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val bi = root.optJSONObject("bigrams") ?: JSONObject()
            for (key in bi.keys()) {
                val count = bi.optInt(key, 0)
                if (count <= 0) continue
                staticBigrams[key] = count
                val prev = key.substringBefore('|')
                staticBiTotals[prev] = (staticBiTotals[prev] ?: 0) + count
            }
            val tri = root.optJSONObject("trigrams") ?: JSONObject()
            for (key in tri.keys()) {
                val count = tri.optInt(key, 0)
                if (count <= 0) continue
                staticTrigrams[key] = count
                val ctx = key.substringBeforeLast('|')
                staticTriTotals[ctx] = (staticTriTotals[ctx] ?: 0) + count
            }
        } catch (_: Exception) {}
    }

    private fun loadPersonalDict() {
        try {
            val f = File(context.filesDir, PERSONAL_DICT)
            if (!f.exists()) return
            val json = JSONObject(f.readText())
            if (json.has("words") && json.optJSONObject("words") != null) {
                // Current format: {"words": {...}, "bigrams": {...}, "trigrams": {...}}.
                val words = json.optJSONObject("words") ?: JSONObject()
                for (key in words.keys()) {
                    personalDict[key] = words.optInt(key, 1)
                }
                val bigrams = json.optJSONObject("bigrams") ?: JSONObject()
                for (key in bigrams.keys()) {
                    bigramCounts[key] = bigrams.optInt(key, 1)
                }
                val trigrams = json.optJSONObject("trigrams") ?: JSONObject()
                for (key in trigrams.keys()) {
                    trigramCounts[key] = trigrams.optInt(key, 1)
                }
            } else {
                // Legacy flat format: {"word": count}. Treat every entry as a word.
                for (key in json.keys()) {
                    personalDict[key] = json.optInt(key, 1)
                }
                personalDirty = true
            }
        } catch (_: Exception) {}
    }

    private fun savePersonalDict() {
        if (!personalDirty) return
        try {
            val json = JSONObject()
            val words = JSONObject()
            for ((word, count) in personalDict) {
                words.put(word, count)
            }
            json.put("words", words)
            val bigrams = JSONObject()
            synchronized(bigramCounts) {
                for ((key, count) in bigramCounts) bigrams.put(key, count)
            }
            json.put("bigrams", bigrams)
            val trigrams = JSONObject()
            synchronized(trigramCounts) {
                for ((key, count) in trigramCounts) trigrams.put(key, count)
            }
            json.put("trigrams", trigrams)
            File(context.filesDir, PERSONAL_DICT).writeText(json.toString())
            personalDirty = false
        } catch (_: Exception) {}
    }

    fun recordWord(raw: String) {
        val lc = raw.lowercase().trimEnd(',', '.', '?', '!', ';', ':', '"', '\'', ')', ']', '}', '>')
        if (lc.isEmpty() || lc.any { !it.isLetter() && it != '\'' }) return
        personalDict[lc] = (personalDict[lc] ?: 0) + 1
        personalDirty = true
    }

    /**
     * Records a bigram observation ("prev|next", lowercase). Same key format
     * as the static asset so both tiers merge in the scorer.
     */
    fun recordBigram(prevWord: String, nextWord: String) {
        val prev = prevWord.trim().lowercase()
        val next = nextWord.trim().lowercase()
        if (prev.isEmpty() || next.isEmpty()) return
        if (prev.any { !it.isLetter() && it != '\'' } || next.any { !it.isLetter() && it != '\'' }) return
        synchronized(bigramCounts) {
            val key = "$prev|$next"
            bigramCounts[key] = (bigramCounts[key] ?: 0) + 1
            while (bigramCounts.size > BIGRAM_MAX_ENTRIES) {
                val eldest = bigramCounts.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        personalDirty = true
    }

    /**
     * Records a trigram observation ("w1|w2|w3", lowercase). Same key format
     * as the static asset so both tiers merge in the scorer.
     */
    fun recordTrigram(w1: String, w2: String, w3: String) {
        val a = w1.trim().lowercase()
        val b = w2.trim().lowercase()
        val c = w3.trim().lowercase()
        if (a.isEmpty() || b.isEmpty() || c.isEmpty()) return
        if (a.any { !it.isLetter() && it != '\'' } || b.any { !it.isLetter() && it != '\'' } ||
            c.any { !it.isLetter() && it != '\'' }
        ) return
        synchronized(trigramCounts) {
            val key = "$a|$b|$c"
            trigramCounts[key] = (trigramCounts[key] ?: 0) + 1
            while (trigramCounts.size > TRIGRAM_MAX_ENTRIES) {
                val eldest = trigramCounts.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        personalDirty = true
    }

    override suspend fun preload(subtype: Subtype) {}

    // ---- Unified N-gram scorer (stupid backoff + personal tiers) ----

    /**
     * Unigram tier: corpus frequency scaled to [0, 1] plus the personal
     * count blended in. Single source of truth (ranking backoff tier and
     * frequency API agree — previously they used different formulas).
     */
    private fun unigramProb(word: String): Double {
        val corpus = wordFrequencies[word] ?: 0
        val personal = personalDict[word] ?: 0
        // 50M was the old saturation point of the frequency API; reuse it as
        // the corpus scale so scores stay comparable to historical behavior.
        val normCorpus = (corpus / 50_000_000.0).coerceIn(0.0, 1.0)
        val normPersonal = (personal.coerceAtMost(25) / 25.0)
        return (normCorpus + 0.5 * normPersonal) / 1.5
    }

    private fun userBiCount(key: String): Int {
        return synchronized(bigramCounts) { bigramCounts[key] ?: 0 }
    }

    private fun userTriCount(key: String): Int {
        return synchronized(trigramCounts) { trigramCounts[key] ?: 0 }
    }

    private fun userBiTotal(ctx: String): Int {
        val prefix = "$ctx|"
        return synchronized(bigramCounts) {
            var total = 0
            for ((key, count) in bigramCounts) {
                if (key.startsWith(prefix)) total += count
            }
            total
        }
    }

    private fun userTriTotal(ctx: String): Int {
        val prefix = "$ctx|"
        return synchronized(trigramCounts) {
            var total = 0
            for ((key, count) in trigramCounts) {
                if (key.startsWith(prefix)) total += count
            }
            total
        }
    }

    /**
     * P(word | history) with stupid backoff over combined static + personal
     * tiers (same semantics as the Vietnamese provider).
     */
    fun ngramProb(history: List<String>, word: String): Double {
        val hist = history.takeLast(2).map { it.lowercase() }
        val w = word.lowercase()
        if (hist.size >= 2) {
            val ctx = "${hist[0]}|${hist[1]}"
            val key = "$ctx|$w"
            val num = (staticTrigrams[key] ?: 0) + USER_OBS_WEIGHT * userTriCount(key)
            if (num > 0) {
                val den = (staticTriTotals[ctx] ?: 0) + USER_OBS_WEIGHT * userTriTotal(ctx)
                if (den > 0) return (num / den).coerceIn(0.0, 1.0)
            }
            return BACKOFF_WEIGHT * bigramOrUni(hist[1], w)
        }
        if (hist.size == 1) {
            return bigramOrUni(hist[0], w)
        }
        return unigramProb(w)
    }

    private fun bigramOrUni(ctx: String, word: String): Double {
        val key = "$ctx|$word"
        val num = (staticBigrams[key] ?: 0) + USER_OBS_WEIGHT * userBiCount(key)
        if (num > 0) {
            val den = (staticBiTotals[ctx] ?: 0) + USER_OBS_WEIGHT * userBiTotal(ctx)
            if (den > 0) return (num / den).coerceIn(0.0, 1.0)
        }
        return BACKOFF_WEIGHT * unigramProb(word)
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: EditorContent,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        if (isPrivateSession) return emptyList()
        // At a word boundary predict the NEXT word from N-gram history.
        if (isWordBoundary(content)) {
            return nextWordCandidates(ngramHistory(content, atBoundary = true), maxCandidateCount)
        }
        val textBefore = content.textBeforeSelection
        val prefix = textBefore.substringAfterLast(' ').substringAfter('\n').lowercase()
        if (prefix.isEmpty()) return emptyList()
        val history = ngramHistory(content, atBoundary = false)

        val found = mutableSetOf<String>()
        val pool = mutableListOf<String>()
        for (word in prefixMap[prefix].orEmpty()) {
            if (found.add(word)) {
                pool.add(word)
                if (pool.size >= maxCandidateCount * 2) break
            }
        }
        for (word in personalDict.keys) {
            if (word.startsWith(prefix) && found.add(word)) {
                pool.add(word)
                if (pool.size >= maxCandidateCount * 2) break
            }
        }
        return pool
            .map { word -> word to ngramProb(history, word) }
            .sortedByDescending { it.second }
            .take(maxCandidateCount)
            .mapIndexed { index, (word, prob) ->
                WordSuggestionCandidate(
                    text = word,
                    confidence = ((prob / (prob + 0.08)) * (1.0 - index.toDouble() / maxCandidateCount))
                        .coerceIn(0.05, 0.99),
                    sourceProvider = this,
                )
            }
    }

    /**
     * Next-word prediction at a word boundary (mirrors the Vietnamese
     * provider; committing one chains into further predictions).
     */
    private fun nextWordCandidates(history: List<String>, maxCandidateCount: Int): List<SuggestionCandidate> {
        if (history.isEmpty() || maxCandidateCount <= 0) return emptyList()
        val ctx = history.last().lowercase()
        if (ctx.isEmpty()) return emptyList()
        val combined = HashMap<String, Double>()
        val prefix = "$ctx|"
        for ((key, count) in staticBigrams) {
            if (key.startsWith(prefix)) {
                val next = key.substring(prefix.length)
                combined[next] = (combined[next] ?: 0.0) + count
            }
        }
        synchronized(bigramCounts) {
            for ((key, count) in bigramCounts) {
                if (key.startsWith(prefix)) {
                    val next = key.substring(prefix.length)
                    combined[next] = (combined[next] ?: 0.0) + USER_OBS_WEIGHT * count
                }
            }
        }
        if (combined.isEmpty()) return emptyList()
        return combined.entries
            .sortedByDescending { it.value }
            .take(maxCandidateCount)
            .mapIndexed { index, (word, _) ->
                val prob = ngramProb(history, word)
                WordSuggestionCandidate(
                    text = word,
                    confidence = ((prob / (prob + 0.08)) * (1.0 - index.toDouble() / maxCandidateCount))
                        .coerceIn(0.05, 0.99),
                    isEligibleForAutoCommit = false,
                    sourceProvider = this,
                )
            }
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        recordWord(candidate.text.toString())
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {}

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean = false

    override suspend fun getListOfWords(subtype: Subtype): List<String> =
        (wordFrequencies.keys + personalDict.keys).toList()

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return unigramProb(word.lowercase()).coerceIn(0.0, 1.0)
    }

    override suspend fun getBigramFrequencyFor(prevWord: String, nextWord: String): Double {
        if (prevWord.isBlank() || nextWord.isBlank()) return 0.0
        val ctx = prevWord.trim().lowercase()
        val next = nextWord.trim().lowercase()
        val key = "$ctx|$next"
        val num = (staticBigrams[key] ?: 0) + USER_OBS_WEIGHT * userBiCount(key)
        if (num <= 0) return 0.0
        val den = (staticBiTotals[ctx] ?: 0) + USER_OBS_WEIGHT * userBiTotal(ctx)
        if (den <= 0) return 0.0
        return (num / den).coerceIn(0.0, 1.0)
    }

    override suspend fun rerankGlideSuggestions(
        subtype: Subtype,
        textBefore: String,
        candidates: List<String>,
    ): List<String> {
        if (candidates.size < 2) return candidates
        val prevWord = textBefore.substringAfterLast(' ').trim().trimEnd(',', '.', '?', '!', ';', ':')
        // Same geometry-first fusion as the Vietnamese provider.
        return candidates.mapIndexed { index, candidate ->
            candidate to (getBigramFrequencyFor(prevWord, candidate) + GEO_PRIOR / (index + 1))
        }.sortedByDescending { it.second }.map { it.first }
    }

    override suspend fun destroy() {
        if (personalDirty) savePersonalDict()
        bgScope.cancel()
    }
}
