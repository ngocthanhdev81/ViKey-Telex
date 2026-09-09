/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ngocthanhgl.vikey.ime.nlp

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import dev.ngocthanhgl.vikey.app.FlorisPreferenceStore
import dev.ngocthanhgl.vikey.clipboardManager
import dev.ngocthanhgl.vikey.editorInstance
import dev.ngocthanhgl.vikey.ime.clipboard.provider.ClipboardItem
import dev.ngocthanhgl.vikey.ime.clipboard.provider.ItemType
import dev.ngocthanhgl.vikey.ime.core.Subtype
import dev.ngocthanhgl.vikey.ime.editor.EditorContent
import dev.ngocthanhgl.vikey.ime.editor.EditorRange
import dev.ngocthanhgl.vikey.ime.editor.InputAttributes
import dev.ngocthanhgl.vikey.ime.media.emoji.EmojiSuggestionProvider
import dev.ngocthanhgl.vikey.ime.nlp.english.EnglishSuggestionProvider
import dev.ngocthanhgl.vikey.ime.nlp.vietnamese.VietnameseLanguageProvider
import dev.ngocthanhgl.vikey.keyboardManager
import dev.ngocthanhgl.vikey.lib.util.NetworkUtils
import dev.ngocthanhgl.vikey.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.florisboard.lib.kotlin.guardedByLock
import org.florisboard.lib.kotlin.collectLatestIn
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val BLANK_STR_PATTERN = "^\\s*$"

class NlpManager(context: Context) {
    private val blankStrRegex = Regex(BLANK_STR_PATTERN)

    private val prefs by FlorisPreferenceStore
    private val clipboardManager by context.clipboardManager()
    private val editorInstance by context.editorInstance()
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val clipboardSuggestionProvider = ClipboardSuggestionProvider(context)
    private val emojiSuggestionProvider = EmojiSuggestionProvider(context)
    private val providers = guardedByLock {
        mapOf(
            EnglishSuggestionProvider.ProviderId to ProviderInstanceWrapper(EnglishSuggestionProvider(context)),
            VietnameseLanguageProvider.ProviderId to ProviderInstanceWrapper(VietnameseLanguageProvider(context)),
        )
    }
    private var currentShiftState: dev.ngocthanhgl.vikey.ime.input.InputShiftState = dev.ngocthanhgl.vikey.ime.input.InputShiftState.UNSHIFTED
    @Volatile
    private var hasPendingComposition = false
    @Volatile
    private var lastPrefix: String? = null
    @Volatile
    private var lastShiftSeen: dev.ngocthanhgl.vikey.ime.input.InputShiftState? = null
    @Volatile
    private var pendingCompletion: String? = null
    /** Last 2 fully completed words (oldest first): N-gram learning history. */
    private val completedHistory = ArrayDeque<String>(2)

    private fun pushCompleted(word: String) {
        if (completedHistory.size >= 2) completedHistory.removeFirst()
        completedHistory.addLast(word)
    }

    fun hasPendingCompositionSuggestion(): Boolean = hasPendingComposition

    private var suggestJob: Job? = null
    private var compositionJob: Job? = null

    fun onStartInput() {
        clearCompositionState()
        suggestJob?.cancel()
        suggestJob = null
    }

    fun onFinishInput() {
        clearCompositionState()
        suggestJob?.cancel()
        suggestJob = null
    }

    fun clearCompositionState() {
        flushPendingCompletion()
        lastPrefix = null
        lastShiftSeen = null
    }

    /**
     * Drops the in-progress composition WITHOUT learning it. Use at word
     * boundaries where the completed word is recorded through another path
     * (suggestion accept, autocorrect commit) or was deleted by the user.
     */
    fun discardPendingCompletion() {
        pendingCompletion = null
    }

    /**
     * Tracks the in-progress composition so that when a word boundary arrives
     * (whitespace via [clearCompositionState] or a punctuation keystroke) the just
     * completed word can be learned into the personal dictionary and bigram model.
     */
    private fun noteCompositionProgress(prefix: String) {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) {
            flushPendingCompletion()
            return
        }
        if (trimmed.length == 1 && !trimmed[0].isLetter()) {
            // Punctuation terminates the word being typed.
            flushPendingCompletion()
            return
        }
        if (trimmed.any { !it.isLetter() }) {
            flushPendingCompletion()
            return
        }
        pendingCompletion = trimmed.lowercase(Locale.ROOT)
    }

    private fun flushPendingCompletion() {
        val word = pendingCompletion ?: return
        pendingCompletion = null
        if (word.length < 2 || keyboardManager.activeState.isIncognitoMode) return
        val subtype = subtypeManager.activeSubtype
        val history = completedHistory.toList()
        scope.launch(Dispatchers.IO) {
            when (val provider = getSuggestionProvider(subtype)) {
                is VietnameseLanguageProvider -> {
                    provider.recordWord(word)
                    if (history.isNotEmpty()) provider.recordBigram(history.last(), word)
                    if (history.size >= 2) provider.recordTrigram(history[0], history[1], word)
                }
                is EnglishSuggestionProvider -> {
                    provider.recordWord(word)
                    if (history.isNotEmpty()) provider.recordBigram(history.last(), word)
                    if (history.size >= 2) provider.recordTrigram(history[0], history[1], word)
                }
                else -> {}
            }
            pushCompleted(word)
        }
    }

    private val internalSuggestions = AtomicReference(SystemClock.uptimeMillis() to listOf<SuggestionCandidate>())

    private val _activeCandidatesFlow = MutableStateFlow(listOf<SuggestionCandidate>())
    val activeCandidatesFlow = _activeCandidatesFlow.asStateFlow()
    inline var activeCandidates
        get() = activeCandidatesFlow.value
        private set(v) {
            _activeCandidatesFlow.value = v
        }

    val debugOverlaySuggestionsInfos = LruCache<Long, Pair<String, SpellingResult>>(10)
    var debugOverlayVersion = MutableStateFlow(0)

    init {
        clipboardManager.primaryClipFlow.collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.suggestion.enabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.clipboard.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        prefs.emoji.suggestionEnabled.asFlow().collectLatestIn(scope) {
            assembleCandidates()
        }
        subtypeManager.activeSubtypeFlow.collectLatestIn(scope) { subtype ->
            preload(subtype)
        }
        prefs.correction.autoCorrect.asFlow().collectLatestIn(scope) {
            val prefix = lastPrefix
            if (prefix != null) {
                suggestComposition(prefix, keyboardManager.activeState.inputShiftState)
            } else {
                suggest(subtypeManager.activeSubtype, editorInstance.activeContent)
            }
        }
        keyboardManager.activeState.collectLatestIn(scope) { state ->
            val currentShift = state.inputShiftState
            if (currentShift == lastShiftSeen) return@collectLatestIn
            lastShiftSeen = currentShift

            currentShiftState = currentShift
            recaseSuggestions(currentShift)
        }
    }

    /**
     * Gets the punctuation rule from the currently active subtype and returns it. Falls back to a default one if the
     * subtype does not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getActivePunctuationRule(): PunctuationRule {
        return getPunctuationRule(subtypeManager.activeSubtype)
    }

    /**
     * Gets the punctuation rule from the given subtype and returns it. Falls back to a default one if the subtype does
     * not exist or defines an invalid punctuation rule.
     *
     * @return The punctuation rule or a fallback.
     */
    fun getPunctuationRule(subtype: Subtype): PunctuationRule {
        return keyboardManager.resources.punctuationRules.value[subtype.punctuationRule] ?: PunctuationRule.Fallback
    }

    private suspend fun getSpellingProvider(subtype: Subtype): SpellingProvider {
        return providers.withLock { it[subtype.nlpProviders.spelling] }?.provider as? SpellingProvider
            ?: FallbackNlpProvider
    }

    @Volatile
    private var cachedForcesSuggestionOn = false

    private suspend fun getSuggestionProvider(subtype: Subtype): SuggestionProvider {
        return providers.withLock { it[subtype.nlpProviders.suggestion] }?.provider as? SuggestionProvider
            ?: FallbackNlpProvider
    }

    fun preload(subtype: Subtype) {
        scope.launch {
            emojiSuggestionProvider.preload(subtype)
            providers.withLock { providers ->
                subtype.nlpProviders.forEach { _, providerId ->
                    providers[providerId]?.let { provider ->
                        provider.createIfNecessary()
                        provider.preload(subtype)
                    }
                }
            }
            cachedForcesSuggestionOn = getSuggestionProvider(subtype).forcesSuggestionOn
        }
    }

    /**
     * Spell wrapper helper which calls the spelling provider and returns the result. Coroutine management must be done
     * by the source spell checker service.
     */
    suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
    ): SpellingResult {
        return getSpellingProvider(subtype).spell(
            subtype = subtype,
            word = word,
            precedingWords = precedingWords,
            followingWords = followingWords,
            maxSuggestionCount = maxSuggestionCount,
            allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
            isPrivateSession = keyboardManager.activeState.isIncognitoMode,
        )
    }

    suspend fun determineLocalComposing(
        textBeforeSelection: CharSequence, breakIterators: BreakIteratorGroup, localLastCommitPosition: Int
    ): EditorRange {
        return getSuggestionProvider(subtypeManager.activeSubtype).determineLocalComposing(
            subtypeManager.activeSubtype, textBeforeSelection, breakIterators, localLastCommitPosition
        )
    }

    fun providerForcesSuggestionOn(@Suppress("UNUSED_PARAMETER") subtype: Subtype): Boolean {
        return cachedForcesSuggestionOn
    }

    fun isSuggestionOn(): Boolean {
        if (!prefs.suggestion.enabled.get()
            && !prefs.emoji.suggestionEnabled.get()
            && !providerForcesSuggestionOn(subtypeManager.activeSubtype)) return false
        val attrs = editorInstance.activeInfo.inputAttributes
        if (attrs.variation == InputAttributes.Variation.PASSWORD) return false
        if (attrs.variation == InputAttributes.Variation.URI) return false
        return true
    }

    fun liveSuggestionsEnabled(): Boolean {
        if (!isSuggestionOn()) return false
        return true
    }

    fun suggestComposition(prefix: String, shiftState: dev.ngocthanhgl.vikey.ime.input.InputShiftState) {
        if (!liveSuggestionsEnabled()) return
        compositionJob?.cancel()
        lastPrefix = prefix
        lastShiftSeen = shiftState
        noteCompositionProgress(prefix)
        // `prefix` is the locally reconstructed full text (history + in-progress
        // word); the last token is the composition itself, everything before it
        // is N-gram history for the hot path.
        val history = ngramTokens(prefix).dropLast(1).takeLast(2)
        val reqTime = SystemClock.uptimeMillis()
        hasPendingComposition = true
        // KeyboardManager resets inputShiftState right after each keystroke; the resulting
        // spurious UNSHIFTED emission must not recase away the casing we are about to store.
        skipNextRecase = true
        compositionJob = scope.launch {
            val subtype = subtypeManager.activeSubtype
            currentShiftState = shiftState
            val suggestions = getSuggestionProvider(subtype).suggest(
                subtype = subtype,
                content = EditorContent.compositionPrefix(prefix, history),
                maxCandidateCount = 8,
                allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                isPrivateSession = keyboardManager.activeState.isIncognitoMode,
            ).map { candidate ->
                // Carry the shift state this word was typed with so assembleCandidates()
                // never falls back to a stale (already reset) currentShiftState.
                if (candidate is WordSuggestionCandidate) {
                    candidate.copy(shiftState = shiftState)
                } else {
                    candidate
                }
            }
            while (true) {
                val stored = internalSuggestions.get()
                val (prevTime, _) = stored
                if (prevTime < reqTime) {
                    if (internalSuggestions.compareAndSet(stored, reqTime to suggestions)) break
                } else break
            }
            assembleCandidates()
            hasPendingComposition = false
        }
    }

    fun suggest(subtype: Subtype, content: EditorContent) {
        suggestJob?.cancel()
        val reqTime = SystemClock.uptimeMillis()
        if (!isSuggestionOn()) return
        suggestJob = scope.launch {
            delay(80)
            val emojiSuggestions = when {
                prefs.emoji.suggestionEnabled.get() -> {
                    emojiSuggestionProvider.suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = prefs.emoji.suggestionCandidateMaxCount.get(),
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    )
                }
                else -> emptyList()
            }
            val suggestions = when {
                emojiSuggestions.isNotEmpty() && prefs.emoji.suggestionType.get().prefix.isNotEmpty() -> {
                    emptyList()
                }
                else -> {
                    getSuggestionProvider(subtype).suggest(
                        subtype = subtype,
                        content = content,
                        maxCandidateCount = 8,
                        allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                        isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                    )
                }
            }
            while (true) {
                val stored = internalSuggestions.get()
                val (prevTime, _) = stored
                if (prevTime < reqTime) {
                    val newValue = reqTime to buildList {
                        addAll(emojiSuggestions)
                        addAll(suggestions)
                    }
                    if (internalSuggestions.compareAndSet(stored, newValue)) break
                } else break
            }
            assembleCandidates()
        }
    }

    fun suggestDirectly(suggestions: List<SuggestionCandidate>) {
        val reqTime = SystemClock.uptimeMillis()
        internalSuggestions.set(reqTime to suggestions)
        scope.launch { assembleCandidates() }
    }

    @Volatile
    private var skipNextRecase = false

    fun recaseSuggestions(shiftState: dev.ngocthanhgl.vikey.ime.input.InputShiftState) {
        currentShiftState = shiftState
        val wasFlagged = skipNextRecase
        skipNextRecase = false
        if (wasFlagged && shiftState == dev.ngocthanhgl.vikey.ime.input.InputShiftState.UNSHIFTED) {
            // Spurious emission right after a keystroke: keep freshly composed casing.
            return
        }
        while (true) {
            val current = internalSuggestions.get()
            val recased = current.second.map { candidate ->
                if (candidate is WordSuggestionCandidate) {
                    candidate.copy(
                        text = recase(candidate.text.toString(), shiftState),
                        shiftState = shiftState,
                    )
                } else {
                    candidate
                }
            }
            if (internalSuggestions.compareAndSet(current, current.first to recased)) break
        }
        scope.launch { assembleCandidates() }
    }

    private fun recase(word: String, shiftState: dev.ngocthanhgl.vikey.ime.input.InputShiftState): String = when (shiftState) {
        dev.ngocthanhgl.vikey.ime.input.InputShiftState.CAPS_LOCK -> word.uppercase()
        dev.ngocthanhgl.vikey.ime.input.InputShiftState.SHIFTED_MANUAL,
        dev.ngocthanhgl.vikey.ime.input.InputShiftState.SHIFTED_AUTOMATIC -> word.replaceFirstChar { it.uppercase() }
        dev.ngocthanhgl.vikey.ime.input.InputShiftState.UNSHIFTED -> word.lowercase()
    }

    fun clearSuggestions() {
        val reqTime = SystemClock.uptimeMillis()
        internalSuggestions.set(reqTime to emptyList())
        scope.launch { assembleCandidates() }
    }

    @Volatile
    private var suppressNextAutoCommit = false

    fun suppressNextAutoCommit() {
        suppressNextAutoCommit = true
    }

    @Volatile
    private var suppressNextContentSuggestion = false

    fun suppressSuggestionForNextContentUpdate() {
        suppressNextContentSuggestion = true
    }

    fun consumeSuppressNextContentSuggestion(): Boolean {
        if (suppressNextContentSuggestion) {
            suppressNextContentSuggestion = false
            return true
        }
        return false
    }

    fun getAutoCommitCandidate(): SuggestionCandidate? {
        if (suppressNextAutoCommit) {
            suppressNextAutoCommit = false
            return null
        }
        // Staleness guard: suggestComposition() runs asynchronously, so activeCandidates may
        // still hold suggestions for a previous prefix when space/punctuation arrives. Only
        // auto-commit when the candidate matches the word currently in the editor — otherwise
        // commitCompletion() would silently replace freshly typed text with stale output.
        val currentWord = editorInstance.activeContent.currentWordText?.toString()?.lowercase()
            ?: return null
        return activeCandidates.firstOrNull { it.isEligibleForAutoCommit }
            ?.takeIf { it.text.toString().lowercase() == currentWord }
    }

    fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        scope.launch {
            candidate.sourceProvider?.removeSuggestion(subtype, candidate)
            if (candidate is ClipboardSuggestionCandidate) {
                assembleCandidates()
            } else {
                suggest(subtypeManager.activeSubtype, editorInstance.activeContent)
            }
        }
        return true
    }

    fun getListOfWords(subtype: Subtype): List<String> {
        return runBlocking { getSuggestionProvider(subtype).getListOfWords(subtype) }
    }

    fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return runBlocking { getSuggestionProvider(subtype).getFrequencyForWord(subtype, word) }
    }

    fun rerankGlideSuggestions(subtype: Subtype, textBefore: String, candidates: List<String>): List<String> {
        return runBlocking { getSuggestionProvider(subtype).rerankGlideSuggestions(subtype, textBefore, candidates) }
    }

    fun getFrequenciesForWords(subtype: Subtype, words: List<String>): Map<String, Double> {
        return runBlocking {
            val provider = getSuggestionProvider(subtype)
            words.associateWith { word -> provider.getFrequencyForWord(subtype, word) }
        }
    }

    fun getBigramFrequency(prevWord: String, nextWord: String): Double {
        return runBlocking {
            val subtype = subtypeManager.activeSubtype
            val provider = getSuggestionProvider(subtype)
            provider.getBigramFrequencyFor(prevWord, nextWord)
        }
    }

    /**
     * Explicitly learns one already-completed word (glide commit and suggestion
     * tap paths). Records the word itself plus bigram/trigram observations
     * against [history] (up to 2 preceding words, oldest first). Async on IO
     * like [flushPendingCompletion]; never blocks the input thread.
     *
     * @param skipWordRecord Set when the caller already recorded the word
     *  itself (e.g. via notifySuggestionAccepted) to avoid double counting.
     */
    fun learnWord(word: String, history: List<String> = emptyList(), skipWordRecord: Boolean = false) {
        if (word.length < 2 || keyboardManager.activeState.isIncognitoMode) return
        val subtype = subtypeManager.activeSubtype
        val lc = word.lowercase(Locale.ROOT)
        val hist = history.takeLast(2)
        scope.launch(Dispatchers.IO) {
            val provider = getSuggestionProvider(subtype)
            when (provider) {
                is EnglishSuggestionProvider -> {
                    if (!skipWordRecord) provider.recordWord(lc)
                    if (hist.isNotEmpty()) provider.recordBigram(hist.last(), lc)
                    if (hist.size >= 2) provider.recordTrigram(hist[0], hist[1], lc)
                }
                is VietnameseLanguageProvider -> {
                    if (!skipWordRecord) provider.recordWord(lc)
                    if (hist.isNotEmpty()) provider.recordBigram(hist.last(), lc)
                    if (hist.size >= 2) provider.recordTrigram(hist[0], hist[1], lc)
                }
                else -> {}
            }
            pushCompleted(lc)
        }
    }

    private suspend fun assembleCandidates() {
        val candidates = when {
            isSuggestionOn() -> {
                clipboardSuggestionProvider.suggest(
                    subtype = Subtype.DEFAULT,
                    content = editorInstance.activeContent,
                    maxCandidateCount = 8,
                    allowPossiblyOffensive = !prefs.suggestion.blockPossiblyOffensive.get(),
                    isPrivateSession = keyboardManager.activeState.isIncognitoMode,
                ).ifEmpty {
                    internalSuggestions.get().second.map { candidate ->
                        if (candidate is WordSuggestionCandidate) {
                            val st = candidate.shiftState ?: currentShiftState
                            candidate.copy(
                                text = recase(candidate.text.toString(), st),
                                shiftState = st,
                            )
                        } else candidate
                    }
                }
            }
            else -> emptyList()
        }
        activeCandidates = candidates
        autoExpandCollapseSmartbarActions(candidates.isNotEmpty())
    }

    /**
     * Auto-manages the smartbar shared-actions row against suggestion visibility:
     * suggestions showing → collapse actions to make room for the candidates row;
     * no suggestions (e.g. text deleted) → expand actions (the auto-expand function).
     * Manual toggle still works; the next suggestion update re-applies auto state.
     */
    fun autoExpandCollapseSmartbarActions(hasSuggestions: Boolean) {
        if (!prefs.smartbar.enabled.get()) return
        if (!prefs.smartbar.sharedActionsAutoExpandCollapse.get()) return
        if (!isSuggestionOn()) return
        scope.launch {
            prefs.smartbar.sharedActionsExpanded.set(!hasSuggestions)
        }
    }

    fun addToDebugOverlay(word: String, info: SpellingResult) {
        debugOverlaySuggestionsInfos.put(System.currentTimeMillis(), word to info)
        debugOverlayVersion.update { it + 1 }
    }

    fun clearDebugOverlay() {
        debugOverlaySuggestionsInfos.evictAll()
        debugOverlayVersion.update { it + 1 }
    }

    fun destroy() {
        scope.cancel()
    }

    private class ProviderInstanceWrapper(val provider: NlpProvider) {
        private var isInstanceAlive = AtomicBoolean(false)

        suspend fun createIfNecessary() {
            if (!isInstanceAlive.getAndSet(true)) provider.create()
        }

        suspend fun preload(subtype: Subtype) {
            provider.preload(subtype)
        }

        suspend fun destroyIfNecessary() {
            if (isInstanceAlive.getAndSet(false)) provider.destroy()
        }
    }

    inner class ClipboardSuggestionProvider internal constructor(private val context: Context) : SuggestionProvider {
        private var lastClipboardItemId: Long = -1

        override val providerId = "org.florisboard.nlp.providers.clipboard"

        override suspend fun create() {
            // Do nothing
        }

        override suspend fun preload(subtype: Subtype) {
            // Do nothing
        }

        override suspend fun suggest(
            subtype: Subtype,
            content: EditorContent,
            maxCandidateCount: Int,
            allowPossiblyOffensive: Boolean,
            isPrivateSession: Boolean,
        ): List<SuggestionCandidate> {
            // Check if enabled
            if (!prefs.clipboard.suggestionEnabled.get()) return emptyList()

            val currentItem = validateClipboardItem(clipboardManager.primaryClip, lastClipboardItemId, content.text)
                ?: return emptyList()

            return buildList {
                val now = System.currentTimeMillis()
                if ((now - currentItem.creationTimestampMs) < prefs.clipboard.suggestionTimeout.get() * 1000) {
                    add(ClipboardSuggestionCandidate(currentItem, sourceProvider = this@ClipboardSuggestionProvider, context = context))
                    if (currentItem.isSensitive) {
                        return@buildList
                    }
                    if (currentItem.type == ItemType.TEXT) {
                        val text = currentItem.stringRepresentation()
                        val matches = buildList {
                            addAll(NetworkUtils.getEmailAddresses(text))
                            addAll(NetworkUtils.getUrls(text))
                            addAll(NetworkUtils.getPhoneNumbers(text))
                        }
                        matches.forEachIndexed { i, match ->
                            val isUniqueMatch = matches.subList(0, i).all { prevMatch ->
                                prevMatch.value != match.value && prevMatch.range.intersect(match.range).isEmpty()
                            }
                            if (match.value != text && isUniqueMatch) {
                                add(ClipboardSuggestionCandidate(
                                    clipboardItem = currentItem.copy(
                                        // TODO: adjust regex of phone number so we don't need to manually strip the
                                        //  parentheses from the match results
                                        text = if (match.value.startsWith("(") && match.value.endsWith(")")) {
                                            match.value.substring(1, match.value.length - 1)
                                        } else {
                                            match.value
                                        }
                                    ),
                                    sourceProvider = this@ClipboardSuggestionProvider,
                                    context = context,
                                ))
                            }
                        }
                    }
                }
            }
        }

        override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
            }
        }

        override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
            // Do nothing
        }

        override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
            if (candidate is ClipboardSuggestionCandidate) {
                lastClipboardItemId = candidate.clipboardItem.id
                return true
            }
            return false
        }

        override suspend fun getListOfWords(subtype: Subtype): List<String> {
            return emptyList()
        }

        override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
            return 0.0
        }

        override suspend fun destroy() {
            // Do nothing
        }

        private fun validateClipboardItem(currentItem: ClipboardItem?, lastItemId: Long, contentText: String) =
            currentItem?.takeIf {
                // Check if already used
                it.id != lastItemId
                    // Check if content is empty
                    && contentText.isBlank()
                    // Check if clipboard content has any valid characters
                    && !currentItem.text.isNullOrBlank()
                    && !blankStrRegex.matches(currentItem.text)
            }
    }
}
