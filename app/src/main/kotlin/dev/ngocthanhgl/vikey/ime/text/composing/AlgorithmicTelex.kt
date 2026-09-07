/*
 * Copyright (C) 2026 NgocThanhGL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.ngocthanhgl.vikey.ime.text.composing

import dev.ngocthanhgl.vikey.app.FlorisPreferenceStore
import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
@SerialName("telex-algorithm")
class AlgorithmicTelex(
    override val id: String = "telex",
    override val label: String = "Telex",
) : Composer {

    override val toRead = 32

    @Transient
    private val prefs by FlorisPreferenceStore

    private val telexWEnabled: Boolean
        get() = try { prefs.keyboard.telexWEnabled.get() } catch (_: Exception) { true }

    companion object {
        val VOWEL_SPLIT_REGEX = Regex("[aeiouyăâêôơ]")
        val DISTANT_MODIFIERS = setOf('a', 'e', 'o', 'w')
    }

    // ── Character classification ──────────────────────────────────

    private val toneKeys = setOf('s', 'f', 'r', 'x', 'j')

    private val baseVowels = setOf(
        'a', 'ă', 'â', 'e', 'ê', 'i',
        'o', 'ô', 'ơ', 'u', 'ư', 'y',
    )

    // ── Vietnamese onset consonants (longest first) ───────────────

    private val knownOnsets = listOf(
        "ngh", "ng", "ch", "gh", "gi", "kh", "nh", "ph", "th", "tr", "qu",
        "b", "c", "d", "đ", "g", "h", "k", "l", "m", "n",
        "p", "r", "s", "t", "v", "x",
    )

    // ── Vietnamese coda consonants ────────────────────────────────

    private val knownCodas = listOf(
        "ch", "ng", "nh", "c", "m", "n", "p", "t",
    )

    private val semivowelCodas = setOf('u', 'i', 'y', 'o')

    // ── Telex shortcut maps ───────────────────────────────────────

    private val shortcuts2 = mapOf(
        "aw" to 'ă',
        "aa" to 'â',
        "ee" to 'ê',
        "oo" to 'ô',
        "ow" to 'ơ',
        "uw" to 'ư',
        "dd" to 'đ',
    )

    private val shortcuts3 = mapOf(
        "uow" to "ươ",
    )

    private val reverseShortcuts = mapOf(
        'ă' to ('a' to 'w'),
        'â' to ('a' to 'a'),
        'ê' to ('e' to 'e'),
        'ô' to ('o' to 'o'),
        'ơ' to ('o' to 'w'),
        'ư' to ('u' to 'w'),
        'đ' to ('d' to 'd'),
    )

    // ── Tone maps ─────────────────────────────────────────────────

    private val toneMaps = mapOf(
        's' to mapOf(
            'a' to 'á', 'ă' to 'ắ', 'â' to 'ấ',
            'e' to 'é', 'ê' to 'ế',
            'i' to 'í',
            'o' to 'ó', 'ô' to 'ố', 'ơ' to 'ớ',
            'u' to 'ú', 'ư' to 'ứ', 'y' to 'ý',
        ),
        'f' to mapOf(
            'a' to 'à', 'ă' to 'ằ', 'â' to 'ầ',
            'e' to 'è', 'ê' to 'ề',
            'i' to 'ì',
            'o' to 'ò', 'ô' to 'ồ', 'ơ' to 'ờ',
            'u' to 'ù', 'ư' to 'ừ', 'y' to 'ỳ',
        ),
        'r' to mapOf(
            'a' to 'ả', 'ă' to 'ẳ', 'â' to 'ẩ',
            'e' to 'ẻ', 'ê' to 'ể',
            'i' to 'ỉ',
            'o' to 'ỏ', 'ô' to 'ổ', 'ơ' to 'ở',
            'u' to 'ủ', 'ư' to 'ử', 'y' to 'ỷ',
        ),
        'x' to mapOf(
            'a' to 'ã', 'ă' to 'ẵ', 'â' to 'ẫ',
            'e' to 'ẽ', 'ê' to 'ễ',
            'i' to 'ĩ',
            'o' to 'õ', 'ô' to 'ỗ', 'ơ' to 'ỡ',
            'u' to 'ũ', 'ư' to 'ữ', 'y' to 'ỹ',
        ),
        'j' to mapOf(
            'a' to 'ạ', 'ă' to 'ặ', 'â' to 'ậ',
            'e' to 'ẹ', 'ê' to 'ệ',
            'i' to 'ị',
            'o' to 'ọ', 'ô' to 'ộ', 'ơ' to 'ợ',
            'u' to 'ụ', 'ư' to 'ự', 'y' to 'ỵ',
        ),
    )

    private val reverseToneMaps: Map<Char, Pair<Char, Char>> = toneMaps.entries
        .flatMap { (toneKey, map) -> map.entries.map { it.value to (it.key to toneKey) } }
        .toMap()

    // ── Vietnamese orthographic tone placement rules ──────────────

    private val toneRules = mapOf(
        "oa" to 'a', "oe" to 'e', "uy" to 'y',
        "ưa" to 'ư', "ươ" to 'ơ', "uô" to 'ô',
        "ua" to 'u', "iê" to 'ê', "yê" to 'ê',
        "uyê" to 'ê', "uya" to 'y',
        "uôi" to 'ô', "ươi" to 'ơ', "ươu" to 'ơ',
        "oai" to 'a', "oay" to 'a', "uay" to 'a',
        "oeo" to 'e', "oeu" to 'e',
        "ia" to 'i', "ya" to 'y',
        "iêu" to 'ê', "yêu" to 'ê',
        // NOTE: there are deliberately NO "ie"/"ye"/"ieu"/"yeu"/"eu" → 'ê'
        // entries. Unikey has no such promotions (proven: "yes"→"ýe",
        // "diets"→"diét", "inter"→"intẻ"); every iê/yê/êu word is typed
        // with a doubled e ("tieen", "yeen", "yeeu"). The genuine
        // single-spelling promotions live in promotionRules below.
        "ai" to 'a', "ay" to 'a', "au" to 'a', "ao" to 'a',
        "oi" to 'o', "ôi" to 'ô', "ơi" to 'ơ',
        "ui" to 'u', "ưi" to 'ư',
        "eo" to 'e', "êu" to 'ê',
        "iu" to 'i', "ưu" to 'ư',
        "ây" to 'â',
    )

    // ── Legal Vietnamese rhymes (order-independence validity gate) ──

    private val legalRhymes: Set<String> = buildSet {
        addAll(
            listOf(
                "a", "ă", "â", "e", "ê", "i", "y", "o", "ô", "ơ", "u", "ư",
                "ai", "ay", "ây", "ao", "au", "âu", "eo", "êu", "iu",
                "oi", "ôi", "ơi", "ui", "ưi", "ưu",
                "ia", "ya", "ua", "ưa", "oa", "oă", "oe", "uy", "uya", "uơ",
                "oai", "oay", "uay",
                "iêu", "yêu", "uôi", "ươi", "ươu",
            ),
        )
        val closed = mapOf(
            "a" to "m n ng nh p t c ch",
            "ă" to "m n ng p t c",
            "â" to "m n ng p t c",
            "e" to "m n ng p t c",
            "ê" to "m n ng nh ch t p",
            "i" to "m n nh ng ch t p",
            "o" to "m n ng p t c",
            "ô" to "m n ng p t c",
            "ơ" to "n ng m t p",
            "u" to "m n ng p t c",
            "ư" to "n ng c t p",
            "iê" to "n ng c t m p",
            "yê" to "n t m",
            "uô" to "n ng c t",
            "ươ" to "n ng c t m",
            "uyê" to "n t",
            "oa" to "n t c ch",
            "oă" to "n t c",
            "oe" to "t",
            "ua" to "ch",
            "uâ" to "n t c",
        )
        for ((nucleus, codas) in closed) {
            for (coda in codas.split(" ")) add(nucleus + coda)
        }
        // Plain spellings (modifier key not typed yet), e.g. "duoc" mid-state
        addAll(
            ("uo uon uoc uong uot uom ie ien iec ieng iem iep iet " +
                "ye yen yet yem uye uyen uyet uoi yeu ieu")
                .split(" "),
        )
    }

    private fun splitRhymeBase(cleanLower: String): String? {
        var remaining = cleanLower
        var matched = false
        for (o in knownOnsets) {
            if (remaining.startsWith(o)) {
                val candidate = remaining.substring(o.length)
                val hasVowel = candidate.any { toBaseForm(it) in baseVowels }
                val multiEndsInVowel = o.length > 1 && toBaseForm(o.last()) in baseVowels
                if (hasVowel || o.length == 1 || !multiEndsInVowel) {
                    remaining = candidate
                    matched = true
                    break
                }
            }
        }
        if (remaining.isEmpty()) return null
        if (!matched) return null
        return remaining
    }

    private fun isValidRhymeWord(displayLower: String): Boolean {
        val base = displayLower.map { toBaseForm(it) }.joinToString("")
        val rhyme = splitRhymeBase(base) ?: return false
        return rhyme in legalRhymes
    }

    // ──────────────────────────────────────────────────────────────
    //  Syllable model
    // ──────────────────────────────────────────────────────────────

    @Suppress("unused")
    private data class Syllable(
        val onset: String = "",
        val nucleus: String = "",
        val coda: String = "",
        val tone: Char? = null,
    )

    // ──────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────

    override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> {
        if (toInsert.length != 1) return 0 to toInsert

        val normalized = if (Normalizer.isNormalized(precedingText, Normalizer.Form.NFC)) {
            precedingText
        } else {
            Normalizer.normalize(precedingText, Normalizer.Form.NFC)
        }
        val ch = toInsert[0]

        if (normalized.isEmpty()) return 0 to firstChar(ch)
        if (!normalized.last().isLetter()) return 0 to ch.toString()

        if (ch.lowercaseChar() == 'z') {
            return handleCancel(normalized)
        }

        val word = lastWord(normalized)
        if (word.isEmpty()) {
            return 0 to firstChar(ch)
        }

        return processWord(word, ch)
    }

    // ──────────────────────────────────────────────────────────────
    //  First character in a new word
    // ──────────────────────────────────────────────────────────────

    private fun firstChar(ch: Char): String {
        if (ch.lowercaseChar() == 'w') {
            if (!telexWEnabled) return ch.toString()
            return if (ch.isUpperCase()) "Ư" else "ư"
        }
        return ch.toString()
    }

    // ──────────────────────────────────────────────────────────────
    //  Process a keypress on the current word (syllable recomposition)
    // ──────────────────────────────────────────────────────────────

    private fun processWord(word: String, ch: Char): Pair<Int, String> {
        val lowerCh = ch.lowercaseChar()

        if (lowerCh in toneKeys) {
            if (word.isNotEmpty()) {
                val candidate = "${word.last().lowercaseChar()}$lowerCh"
                if (knownOnsets.contains(candidate) && word.none { it.lowercaseChar() in baseVowels }) {
                    return word.length to (word + ch)
                }
            }
            return handleTone(word, ch)
        }

        if (lowerCh == 'w' && word.all { it.lowercaseChar() == 'w' }) {
            return word.length to (word + ch)
        }

        if (lowerCh == 'w' && word.length == 1 && word.single().lowercaseChar() == 'ư') {
            // Preserve the case of the word being reverted, not the freshly typed key
            // (shift state may already have been reset): "Ư"+w must yield "W", not "w".
            val result = if (word.single().isUpperCase()) 'W' else 'w'
            return word.length to result.toString()
        }

        if (lowerCh == 'w' && word.last().lowercaseChar() == 'ư' && word.length > 1) {
            val reverted = if (word.last().isUpperCase()) ch.uppercaseChar() else ch
            return word.length to (word.dropLast(1) + reverted)
        }

        if (isShortcutUndo(word, ch)) {
            return doShortcutUndo(word, ch)
        }

        if (lowerCh == 'w') {
            // Try conversions first (e.g. ưo → ươ, u → ư)
            convertUoPair(word)?.let { return word.length to it }

            // Unikey parity: scan right-to-left for the nearest vowel with a
            // w-convertible base; accept the first whose result is a valid
            // Vietnamese rhyme ("muaw"→"mưa" skips invalid "muă";
            // "polkw" refuses "pơlk" and falls through to handleW → "polkư").
            for (pos in findVowelPositions(word).asReversed()) {
                val base = toBaseForm(word[pos].lowercaseChar())
                val target = wTargetFor(base) ?: continue
                if (word[pos].lowercaseChar() == target) continue
                val cand = word.substring(0, pos) +
                    transformVowel(word[pos], target) + word.substring(pos + 1)
                if (isValidRhymeWord(cand.lowercase())) {
                    return word.length to cand
                }
            }

            // Undo only if no conversion worked
            val hasWVowel = word.any {
                val b = it.lowercaseChar()
                b == 'ư' || b == 'ơ'
            }
            if (hasWVowel) {
                val reverted = word.map { c ->
                    when (c.lowercaseChar()) {
                        'ư' -> if (c.isUpperCase()) 'U' else 'u'
                        'ơ' -> if (c.isUpperCase()) 'O' else 'o'
                        else -> c
                    }
                }.joinToString("")
                return word.length to (reverted + ch)
            }

            // No conversion and nothing to undo: standalone-w semantics
            // (Unikey: w with no preceding vowel becomes "ư", e.g. "kw"→"kư",
            // "tr"+"w"→"trư"). handleW also respects telexWEnabled + "ww".
            return handleW(word, ch)
        }

        val shortcut = applyShortcut(word, ch)
        if (shortcut != null) {
            return word.length to shortcut
        }

        val distant = applyDistantShortcut(word, ch)
        if (distant != null) {
            return word.length to distant
        }

        retroactiveDd(word, ch)?.let { return word.length to it }

        return word.length to (word + ch)
    }

    // ──────────────────────────────────────────────────────────────
    //  Tone handling
    // ──────────────────────────────────────────────────────────────

    private fun handleTone(word: String, ch: Char): Pair<Int, String> {
        val toneKey = ch.lowercaseChar()
        val clean = stripTones(word)

        val syllable = parseSyllable(clean.lowercase())
        if (syllable == null || syllable.nucleus.isEmpty()) {
            return word.length to (word + ch)
        }

        // Unikey parity: a tone key only marks when the word ends in a vowel
        // or a valid Vietnamese coda ("quickly"→"quicklý", "mailbox"→"mailbõ",
        // "abandons"→"abandón"; "edicts" stays literal because of "ct").
        val vowelPositions = findVowelPositions(clean)
        if (vowelPositions.isEmpty()) {
            return word.length to (word + ch)
        }
        val tail = clean.lowercase().substring(vowelPositions.last() + 1)
        if (tail.isNotEmpty() && tail !in knownCodas) {
            return word.length to (word + ch)
        }

        val tonePos = resolveTonePosition(clean, syllable)
        if (tonePos < 0) {
            return word.length to (word + ch)
        }

        val current = word[tonePos]
        val base = toBaseForm(current)
        val toned = toneMaps[toneKey]?.get(base) ?: current

        if (current.lowercaseChar() == toned) {
            val before = word.substring(0, tonePos)
            val after = word.substring(tonePos + 1)
            val casedBase = if (current.isUpperCase()) base.uppercaseChar() else base
            return word.length to (before + casedBase + after + ch)
        }

        val chars = word.toCharArray()
        chars[tonePos] = if (current.isUpperCase()) toned.uppercaseChar() else toned
        return word.length to String(chars)
    }

    private val cancelMap = mapOf(
        'ă' to 'a', 'â' to 'a', 'ê' to 'e', 'ô' to 'o',
        'ơ' to 'o', 'ư' to 'u', 'đ' to 'd',
    )

    private fun handleCancel(precedingText: String): Pair<Int, String> {
        val word = lastWord(precedingText)
        if (word.isEmpty()) return 0 to "z"

        val clean = StringBuilder()
        for (c in word) {
            var b = toBaseForm(c)
            cancelMap[b]?.let { b = it }
            clean.append(if (c.isUpperCase()) b.uppercaseChar() else b)
        }
        val result = clean.toString()
        if (result == word) {
            return word.length to (word + "z")
        }
        return word.length to result
    }

    // ──────────────────────────────────────────────────────────────
    //  Shortcut handling
    // ──────────────────────────────────────────────────────────────

    private fun isShortcutUndo(word: String, ch: Char): Boolean {
        if (word.isEmpty()) return false
        val last = word.last().lowercaseChar()
        val expected = reverseShortcuts[last]?.second ?: return false
        return ch.lowercaseChar() == expected
    }

    private fun doShortcutUndo(word: String, ch: Char): Pair<Int, String> {
        val last = word.last().lowercaseChar()
        val pair = reverseShortcuts[last] ?: return 0 to (word + ch)
        val prefix = word.dropLast(1)
        val first = if (word.last().isUpperCase()) {
            pair.first.uppercaseChar()
        } else {
            pair.first
        }
        val second = if (ch.isUpperCase()) pair.second.uppercaseChar() else pair.second
        return word.length to (prefix + first + second)
    }

    private fun applyShortcut(word: String, ch: Char): String? {
        val lowerCh = ch.lowercaseChar()

        if (word.length >= 2) {
            val tail2 = word.substring(word.length - 2).lowercase()
            val key3 = tail2 + lowerCh
            val result3 = shortcuts3[key3]
            if (result3 != null) {
                val mode = casingMode(word.substring(word.length - 2))
                return word.dropLast(2) + applyCasing(result3, mode)
            }
        }

        val last = word.last().lowercaseChar()
        val key2 = "$last$lowerCh"
        val result2 = shortcuts2[key2]
        if (result2 != null) {
            val mode = casingMode(word.last().toString())
            return word.dropLast(1) + applyCasing(result2.toString(), mode)
        }

        return null
    }

    // ── Order-independence helpers (w targeting / uo-pair migration) ──

    private fun wTargetFor(base: Char): Char? = when (base) {
        'a', 'ă' -> 'ă'
        'o', 'ơ' -> 'ơ'
        'u', 'ư' -> 'ư'
        else -> null
    }

    private fun convertUoPair(word: String): String? {
        val positions = findVowelPositions(word)
        for (i in 0 until positions.size - 1) {
            val base1 = toBaseForm(word[positions[i]].lowercaseChar())
            val base2 = toBaseForm(word[positions[i + 1]].lowercaseChar())
            if ((base1 == 'u' || base1 == 'ư') && base2 == 'o') {
                val pi = positions[i]
                val pj = positions[i + 1]
                var toneKey: Char? = null
                for (p in positions) {
                    reverseToneMaps[word[p].lowercaseChar()]?.let { toneKey = it.second }
                }
                val newSecond = toneKey?.let { toneMaps[it]?.get('ơ') } ?: 'ơ'
                val sb = StringBuilder(word)
                sb[pi] = if (word[pi].isUpperCase()) 'Ư' else 'ư'
                sb[pj] = if (word[pj].isUpperCase()) newSecond.uppercaseChar() else newSecond
                for (p in positions) {
                    if (p == pi || p == pj) continue
                    val b = toBaseForm(sb[p])
                    sb[p] = if (word[p].isUpperCase()) b.uppercaseChar() else b
                }
                return sb.toString()
            }
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────
    //  Distant shortcut handling (Unikey standard)
    //  Applies a vowel modifier to the last modifiable vowel of a
    //  valid Vietnamese syllable, even when not typed adjacently.
    // ──────────────────────────────────────────────────────────────

    private fun applyDistantShortcut(word: String, ch: Char): String? {
        val lowerCh = ch.lowercaseChar()
        if (lowerCh !in DISTANT_MODIFIERS) return null

        fun targetForIn(buf: String, pos: Int): Char? {
            val base = toBaseForm(buf[pos].lowercaseChar())
            return when (lowerCh) {
                'a' -> if (base == 'a' || base == 'â') 'â' else null
                'e' -> if (base == 'e' || base == 'ê') 'ê' else null
                'o' -> if (base == 'o' || base == 'ô') 'ô' else null
                'w' -> wTargetFor(base)
                else -> null
            }
        }

        // Unikey parity: scan right-to-left for the nearest vowel with a
        // matching base; accept the first whose result is a valid rhyme
        // ("loio"→"lôi", "taya"→"tây"). If none qualifies, refuse and let
        // the caller append literally ("taia", "leie", "abandona").
        fun scanConvert(buf: String): String? {
            for (pos in findVowelPositions(buf).asReversed()) {
                val target = targetForIn(buf, pos) ?: continue
                if (buf[pos].lowercaseChar() == target) continue
                val cand = buf.substring(0, pos) + transformVowel(buf[pos], target) + buf.substring(pos + 1)
                if (isValidRhymeWord(cand.lowercase())) {
                    return cand
                }
            }
            return null
        }

        scanConvert(word)?.let { return it }

        // Toggle fixed-point (Unikey-verified: "lôi"+o → "loio" but
        // "tâi"+a → "tâia"): when nothing is convertible but something is
        // already modified, revert the rightmost modified vowel — but keep
        // the revert only if re-applying the key to the reverted buffer
        // reproduces this buffer (i.e. it undoes the last effective
        // keypress); otherwise append literally.
        for (pos in findVowelPositions(word).asReversed()) {
            val target = targetForIn(word, pos) ?: continue
            if (word[pos].lowercaseChar() == target) {
                val base = toBaseForm(word[pos].lowercaseChar())
                val revertTarget = reverseShortcuts[base]?.first ?: base
                val reverted = word.substring(0, pos) +
                    transformVowel(word[pos], revertTarget) + word.substring(pos + 1)
                if (scanConvert(reverted) == word) {
                    return reverted + ch
                }
            }
        }

        return null
    }

    private fun transformVowel(current: Char, target: Char): Char {
        val lower = current.lowercaseChar()
        val entry = reverseToneMaps[lower]
        val result = if (entry != null) {
            toneMaps[entry.second]?.get(target) ?: target
        } else {
            target
        }
        return if (current.isUpperCase()) result.uppercaseChar() else result
    }

    // ──────────────────────────────────────────────────────────────
    //  Standalone w → ư / Ư
    // ──────────────────────────────────────────────────────────────

    private fun handleW(word: String, ch: Char): Pair<Int, String> {
        if (ch.isLetter().not()) return word.length to (word + ch)
        if (!telexWEnabled) return word.length to (word + ch)
        val last = word.lastOrNull()

        if (last?.lowercaseChar() == 'w') {
            return word.length to (word + ch)
        }

        val lastBase = last?.let { toBaseForm(it.lowercaseChar()) }
        if (lastBase != null && lastBase in baseVowels) {
            return word.length to (word + ch)
        }

        val uChar = if (ch.isUpperCase()) 'Ư' else 'ư'
        return word.length to (word + uChar)
    }

    // ──────────────────────────────────────────────────────────────
    //  Retroactive dd → đ (order-independent)
    // ──────────────────────────────────────────────────────────────

    private fun retroactiveDd(word: String, ch: Char): String? {
        if (ch.lowercaseChar() != 'd') return null
        val last = word.lastOrNull() ?: return null
        // Unikey standard: only an ADJACENT "dd" pair becomes "đ" — never
        // pair the new 'd' with a distant leading 'd' ("duckd"+d → "duckđ",
        // not "đuckd"). Undo ("đ"+d → "dd") is handled earlier by the
        // generic isShortcutUndo path, so it is intentionally absent here.
        if (last == 'd' || last == 'D') {
            val dd = if (last == 'D' || ch.isUpperCase()) 'Đ' else 'đ'
            return word.dropLast(1) + dd
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────
    //  Syllable parser
    // ──────────────────────────────────────────────────────────────

    private fun parseSyllable(clean: String): Syllable? {
        if (clean.isEmpty()) return null

        var remaining = clean
        var onset = ""

        for (o in knownOnsets) {
            if (remaining.startsWith(o)) {
                val candidate = remaining.removePrefix(o)
                val hasVowel = candidate.any { toBaseForm(it) in baseVowels }
                val multiEndsInVowel = o.length > 1 && toBaseForm(o.last()) in baseVowels
                if (hasVowel || o.length == 1 || !multiEndsInVowel) {
                    onset = o
                    remaining = candidate
                    break
                }
            }
        }

        if (remaining.isEmpty()) return Syllable(onset = onset)

        var coda = ""

        for (c in knownCodas) {
            if (remaining.endsWith(c)) {
                coda = c
                remaining = remaining.removeSuffix(c)
                break
            }
        }

        if (!coda.isEmpty() && remaining.isEmpty()) {
            return Syllable(onset = onset, nucleus = "", coda = coda)
        }

        if (remaining.endsWith('u') || remaining.endsWith('i') ||
            remaining.endsWith('y') || remaining.endsWith('o')
        ) {
            val last = remaining.last()
            if (remaining.length > 1 && last in semivowelCodas) {
                val before = remaining.dropLast(1)
                if (before.any { toBaseForm(it) in baseVowels }) {
                    coda = last.toString()
                    remaining = before
                }
            }
        }

        if (remaining.isEmpty()) return Syllable(onset = onset, nucleus = "", coda = coda)

        val nucleus = remaining
        return Syllable(onset = onset, nucleus = nucleus, coda = coda)
    }

    // ──────────────────────────────────────────────────────────────
    //  Tone position resolver (Vietnamese orthographic rules)
    // ──────────────────────────────────────────────────────────────

    // Returns the tone position. There are deliberately NO mark-changing
    // promotions here (Unikey-verified: "muois"→"muói", "khuyens"→"khuýen",
    // "vuonf"→"vuòn", "tiens"→"tién") — the rule only positions, the mark
    // comes from doubling/w, never from the tone key itself.
    private fun resolveTonePosition(word: String, syllable: Syllable): Int {
        val vowelPositions = findVowelPositions(word)
        if (vowelPositions.isEmpty()) return -1
        if (vowelPositions.size == 1) return vowelPositions[0]

        val vowelCluster = buildString {
            for (pos in vowelPositions) {
                append(toBaseForm(word[pos].lowercaseChar()))
            }
        }

        val rule = toneRules[vowelCluster]
        if (rule != null) {
            for (pos in vowelPositions) {
                if (toBaseForm(word[pos].lowercaseChar()) == rule) {
                    return pos
                }
            }
        }

        for (pos in vowelPositions) {
            val b = toBaseForm(word[pos].lowercaseChar())
            if (b == 'ê' || b == 'ơ') return pos
        }

        for (pos in vowelPositions) {
            val b = toBaseForm(word[pos].lowercaseChar())
            if (b == 'â' || b == 'ă' || b == 'ô') return pos
        }

        // No rule matched: Unikey marks the rightmost 'y' if present
        // ("yes"→"ýe", "quickly"→"quicklý", "family"→"familý"),
        // otherwise the last vowel ("diets"→"diét", "abandons"→"abandón").
        for (pos in vowelPositions.asReversed()) {
            if (toBaseForm(word[pos].lowercaseChar()) == 'y') return pos
        }

        return vowelPositions.last()
    }

    // ──────────────────────────────────────────────────────────────
    //  Vowel position finder (handles gi/qu exceptions)
    // ──────────────────────────────────────────────────────────────

    private fun findVowelPositions(word: String): List<Int> {
        val lower = word.lowercase()
        val result = mutableListOf<Int>()

        for (i in lower.indices) {
            val c = lower[i]

            if (toBaseForm(c) !in baseVowels) continue

            if (c == 'i' && i == 1 && lower.startsWith("gi") && lower.length > 2) continue

            if (c == 'u' && i == 1 && lower.startsWith("qu") && lower.length > 2) continue

            result.add(i)
        }

        return result
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private fun lastWord(text: String): String {
        val t = text.trimEnd()
        val i = t.lastIndexOf(' ')
        val candidate = if (i < 0) t else t.substring(i + 1)
        return candidate.takeLastWhile { it.isLetter() }
    }

    private fun stripTones(text: String): String {
        return buildString {
            for (c in text) {
                append(toBaseForm(c))
            }
        }
    }

    private fun toBaseForm(c: Char): Char {
        return when (c.lowercaseChar()) {
            'a', 'á', 'à', 'ả', 'ã', 'ạ' -> 'a'
            'ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ' -> 'ă'
            'â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ' -> 'â'
            'e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ' -> 'e'
            'ê', 'ế', 'ề', 'ể', 'ễ', 'ệ' -> 'ê'
            'i', 'í', 'ì', 'ỉ', 'ĩ', 'ị' -> 'i'
            'o', 'ó', 'ò', 'ỏ', 'õ', 'ọ' -> 'o'
            'ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ' -> 'ô'
            'ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ' -> 'ơ'
            'u', 'ú', 'ù', 'ủ', 'ũ', 'ụ' -> 'u'
            'ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự' -> 'ư'
            'y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ' -> 'y'
            'đ' -> 'd'
            else -> c
        }
    }

    private fun casingMode(sample: String): CaseMode {
        val letters = sample.filter { it.isLetter() }
        if (letters.isEmpty()) return CaseMode.LOWER
        if (letters.all { it.isUpperCase() }) return CaseMode.UPPER
        if (letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() }) {
            return CaseMode.CAPITALIZED
        }
        return CaseMode.LOWER
    }

    private fun applyCasing(text: String, mode: CaseMode): String {
        return when (mode) {
            CaseMode.UPPER -> text.uppercase()
            CaseMode.CAPITALIZED -> text.replaceFirstChar { it.uppercase() }
            CaseMode.LOWER -> text
        }
    }

    private enum class CaseMode { LOWER, CAPITALIZED, UPPER }
}
