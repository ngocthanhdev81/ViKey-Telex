/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.ngocthanhgl.vikey.ime.text.gestures

import android.content.Context
import androidx.collection.LruCache
import androidx.collection.SparseArrayCompat
import androidx.collection.set
import dev.ngocthanhgl.vikey.ime.core.Subtype
import dev.ngocthanhgl.vikey.ime.keyboard.KeyData
import dev.ngocthanhgl.vikey.ime.text.key.KeyCode
import dev.ngocthanhgl.vikey.ime.text.keyboard.TextKey
import dev.ngocthanhgl.vikey.ime.text.keyboard.TextKeyData
import dev.ngocthanhgl.vikey.nlpManager
import java.text.Normalizer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private fun TextKey.baseCode(): Int {
    return (data as? KeyData)?.code ?: KeyCode.UNSPECIFIED
}

/**
 * Classifies gestures by comparing them with an "ideal gesture".
 *
 * Check out Étienne Desticourt's excellent write up at https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870
 */
class StatisticalGlideTypingClassifier(context: Context) : GlideTypingClassifier {
    private val nlpManager by context.nlpManager()

    private val gesture = Gesture()
    private var keysByCharacter: SparseArrayCompat<TextKey> = SparseArrayCompat()
    private var words: List<String> = emptyList()
    private var keys: ArrayList<TextKey> = arrayListOf()
    private lateinit var pruner: Pruner
    private var wordDataSubtype: Subtype? = null
    private var layoutSubtype: Subtype? = null
    private var currentSubtype: Subtype? = null
    val ready: Boolean
        get() = currentSubtype == layoutSubtype && wordDataSubtype == layoutSubtype && wordDataSubtype != null
    private val prunerCache = LruCache<Subtype, Pruner>(PRUNER_CACHE_SIZE)

    /**
     * The minimum distance between points to be added to a gesture.
     */
    private var distanceThresholdSquared = 0

    companion object {
        /**
         * Describes the allowed length variance in a gesture. If a gesture is too long or too short, it is immediately
         * discarded to save cycles.
         */
        private const val PRUNING_LENGTH_THRESHOLD = 8.42

        /**
         * describes the number of points to sample a gesture at, i.e the resolution.
         */
        private const val SAMPLING_POINTS: Int = 200

        /**
         * Standard deviation of the distribution of distances between the shapes of two gestures
         * representing the same word. It's expressed for normalized gestures and is therefore
         * independent of the keyboard or key size.
         */
        private const val SHAPE_STD = 22.08f

        /**
         * Standard deviation of the distribution of distances between the locations of two gestures
         * representing the same word. It's expressed as a factor of key radius as it's applied to
         * un-normalized gestures and is therefore dependent on the size of the keys/keyboard.
         */
        private const val LOCATION_STD = 0.5109f

        /**
         * This is a very small cache that caches suggestions, so that they aren't recalculated e.g when releasing
         * a pointer when the suggestions were already calculated. Avoids a lot of micro pauses.
         */
        private const val SUGGESTION_CACHE_SIZE = 5

        /**
         * For multiple subtypes, the pruner is cached.
         */
        private const val PRUNER_CACHE_SIZE = 5
    }

    override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
        if (!gesture.isEmpty) {
            val dx = gesture.getLastX() - position.x
            val dy = gesture.getLastY() - position.y

            if (dx * dx + dy * dy > distanceThresholdSquared) {
                gesture.addPoint(position.x, position.y)
            }
        } else {
            gesture.addPoint(position.x, position.y)
        }
    }

    override fun setLayout(keyViews: List<TextKey>, subtype: Subtype) {
        setWordData(subtype)
        // stop duplicate calls
        if (layoutSubtype == subtype && keys == keyViews) {
            return
        }

        // if only layout changed but not subtype
        val layoutChanged = layoutSubtype == subtype

        keysByCharacter.clear()
        keys.clear()
        keyViews.forEach {
            keysByCharacter[it.baseCode()] = it
            keys.add(it)
        }
        layoutSubtype = subtype
        distanceThresholdSquared = (keyViews.first().visibleBounds.width / 4).toInt()
        distanceThresholdSquared *= distanceThresholdSquared

        if (
            (wordDataSubtype == layoutSubtype)
            || layoutChanged // should force a re-initialize
        ) {
            initializePruner(layoutChanged)
        }
    }

    override fun setWordData(subtype: Subtype) {
        // stop duplicate calls..
        if (wordDataSubtype == subtype) {
            return
        }

        this.words = nlpManager.getListOfWords(subtype)

        this.wordDataSubtype = subtype
        if (wordDataSubtype == layoutSubtype) {
            initializePruner(false)
        }
    }

    /**
     * Exists because Pruner requires both word data and layout are initialized,
     * however we don't know what order they're initialized in.
     */
    private fun initializePruner(invalidateCache: Boolean) {
        val currentSubtype = this.layoutSubtype!!
        val cached = when {
            invalidateCache -> null
            else -> prunerCache.get(currentSubtype)
        }
        if (cached == null) {
            this.pruner = Pruner(PRUNING_LENGTH_THRESHOLD, this.words, keysByCharacter)
            prunerCache.put(currentSubtype, this.pruner)
        } else {
            this.pruner = cached
        }
        this.currentSubtype = currentSubtype
    }

    override fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData) {
        for (position in pointerData.positions) {
            addGesturePoint(position)
        }
    }

    private val lruSuggestionCache = LruCache<Pair<Gesture, Int>, List<String>>(SUGGESTION_CACHE_SIZE)
    override fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<String> {
        return when (val cached = lruSuggestionCache.get(Pair(this.gesture, maxSuggestionCount))) {
            null -> {
                val suggestions = unCachedGetSuggestions(maxSuggestionCount)
                lruSuggestionCache.put(Pair(this.gesture.clone(), maxSuggestionCount), suggestions)

                suggestions
            }
            else -> {
                cached
            }
        }
    }

    private fun unCachedGetSuggestions(maxSuggestionCount: Int): List<String> {
        val candidates = arrayListOf<String>()
        val candidateWeights = arrayListOf<Float>()
        val key = keys.firstOrNull() ?: return listOf()
        val radius = min(key.visibleBounds.height, key.visibleBounds.width)
        var remainingWords = pruner.pruneByExtremities(gesture, this.keys)
        val userGesture = gesture.resample(SAMPLING_POINTS)
        val normalizedUserGesture: Gesture = userGesture.normalizeByBoxSide()
        remainingWords = pruner.pruneByLength(gesture, remainingWords, keysByCharacter, keys)
        // Extremity weighting: points far from the gesture centroid (turns,
        // endpoints — the most informative samples) weigh up to 3x more than
        // points near the centroid (straight transit segments). Indices match
        // between raw and normalized gestures (same resample order).
        val pointWeights = FloatArray(SAMPLING_POINTS)
        var cx = 0.0f
        var cy = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            cx += userGesture.getX(i)
            cy += userGesture.getY(i)
        }
        cx /= SAMPLING_POINTS
        cy /= SAMPLING_POINTS
        var maxDist = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            val dx = userGesture.getX(i) - cx
            val dy = userGesture.getY(i) - cy
            val d = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            pointWeights[i] = d
            if (d > maxDist) maxDist = d
        }
        if (maxDist > 0.0f) {
            for (i in 0 until SAMPLING_POINTS) {
                pointWeights[i] = 0.5f + pointWeights[i] / maxDist
            }
        } else {
            pointWeights.fill(1.0f)
        }

        for (i in remainingWords.indices) {
            val word = remainingWords[i]
            val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter)

            for (idealGesture in idealGestures) {
                val wordGesture = idealGesture.resample(SAMPLING_POINTS)
                val normalizedGesture: Gesture = wordGesture.normalizeByBoxSide()
                val shapeDistance = calcShapeDistance(normalizedGesture, normalizedUserGesture, pointWeights)
                val locationDistance = calcLocationDistance(wordGesture, userGesture, pointWeights)
                val shapeProbability = calcGaussianProbability(shapeDistance, 0.0f, SHAPE_STD)
                val locationProbability = calcGaussianProbability(locationDistance, 0.0f, LOCATION_STD * radius)
                val frequency = 255f * nlpManager.getFrequencyForWord(currentSubtype!!, word).toFloat()
                val confidence = 1.0f / (shapeProbability * locationProbability * frequency)

                var candidateDistanceSortedIndex = 0
                var duplicateIndex = Int.MAX_VALUE

                while (candidateDistanceSortedIndex < candidateWeights.size
                    && candidateWeights[candidateDistanceSortedIndex] <= confidence
                ) {
                    if (candidates[candidateDistanceSortedIndex].contentEquals(word)) duplicateIndex =
                        candidateDistanceSortedIndex
                    candidateDistanceSortedIndex++
                }
                if (candidateDistanceSortedIndex < maxSuggestionCount && candidateDistanceSortedIndex <= duplicateIndex) {
                    if (duplicateIndex < Int.MAX_VALUE) {
                        candidateWeights.removeAt(duplicateIndex)
                        candidates.removeAt(duplicateIndex)
                    }
                    candidateWeights.add(candidateDistanceSortedIndex, confidence)
                    candidates.add(candidateDistanceSortedIndex, word)
                    if (candidateWeights.size > maxSuggestionCount) {
                        candidateWeights.removeAt(maxSuggestionCount)
                        candidates.removeAt(maxSuggestionCount)
                    }
                }
            }
        }

        return candidates
    }

    override fun clear() {
        gesture.clear()
    }

    /**
     * Decodes the raw gesture trail into a letter sequence without consulting
     * the word pool: nearest key per sampled point, consecutive duplicates
     * collapsed. This is the OOV fallback — words missing from the pool
     * (names, slang, loanwords) can never win geometric scoring, so the
     * literal trail is the best commit candidate. Returns null when no
     * sane word-shape exists (too short or no letter keys hit).
     */
    fun decodeTrailLetters(): String? {
        if (gesture.isEmpty || keys.isEmpty()) return null
        val sampled = gesture.resample(SAMPLING_POINTS)
        val sb = StringBuilder()
        var lastCode = -1
        for (i in 0 until SAMPLING_POINTS) {
            val x = sampled.getX(i)
            val y = sampled.getY(i)
            var best: TextKey? = null
            var bestD = Float.MAX_VALUE
            for (key in keys) {
                val c = key.visibleBounds.center
                val dx = c.x - x
                val dy = c.y - y
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = key
                }
            }
            val code = (best?.data as? TextKeyData)?.code ?: continue
            if (code <= 0 || code > 0x10FFFF) {
                lastCode = -1
                continue
            }
            val ch = Character.toChars(code)[0].lowercaseChar()
            if (!ch.isLetter()) {
                // Symbol/space keys break the run but don't abort the decode.
                lastCode = -1
                continue
            }
            if (code == lastCode) continue
            sb.append(ch)
            lastCode = code
        }
        val word = sb.toString()
        return word.takeIf { it.length >= 2 }
    }

    private fun calcLocationDistance(gesture1: Gesture, gesture2: Gesture, weights: FloatArray): Float {
        var totalDistance = 0.0f
        var weightSum = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            val x1 = gesture1.getX(i)
            val x2 = gesture2.getX(i)
            val y1 = gesture1.getY(i)
            val y2 = gesture2.getY(i)
            val distance = abs(x1 - x2) + abs(y1 - y2)
            totalDistance += weights[i] * distance
            weightSum += weights[i]
        }
        return if (weightSum > 0.0f) totalDistance / weightSum / 2 else 0.0f
    }

    private fun calcGaussianProbability(value: Float, mean: Float, standardDeviation: Float): Float {
        val factor = 1.0 / (standardDeviation * sqrt(2 * PI))
        val exponent = ((value - mean) / standardDeviation).toDouble().pow(2.0)
        val probability = factor * exp(-1.0 / 2 * exponent)
        return probability.toFloat()
    }

    private fun calcShapeDistance(gesture1: Gesture, gesture2: Gesture, weights: FloatArray): Float {
        // NOTE: returns the weighted SUM (scaled by SAMPLING_POINTS, like the
        // old unweighted sum) — not the average — because SHAPE_STD is tuned
        // for that scale. With uniform weights this is exactly the old value.
        var distance: Float
        var totalDistance = 0.0f
        var weightSum = 0.0f
        for (i in 0 until SAMPLING_POINTS) {
            val x1 = gesture1.getX(i)
            val x2 = gesture2.getX(i)
            val y1 = gesture1.getY(i)
            val y2 = gesture2.getY(i)
            distance = Gesture.distance(x1, y1, x2, y2)
            totalDistance += weights[i] * distance
            weightSum += weights[i]
        }
        return if (weightSum > 0.0f) totalDistance / weightSum * SAMPLING_POINTS else 0.0f
    }

    class Pruner(
        /**
         * The length difference between a user gesture and a word gesture above which a word will
         * be pruned.
         */
        private val lengthThreshold: Double,
        words: List<String>,
        keysByCharacter: SparseArrayCompat<TextKey>,
    ) {

        /** A tree that provides fast access to words based on their first and last letter.  */
        private val wordTree = Collections.synchronizedMap(HashMap<Pair<Int, Int>, ArrayList<String>>())

        /**
         * Finds the words whose start and end letter are closest to the start and end points of the
         * user gesture.
         *
         * @param userGesture The current user gesture.
         * @param keys The keys on the keyboard.
         * @return A list of likely words.
         */
        fun pruneByExtremities(
            userGesture: Gesture,
            keys: Iterable<TextKey>,
        ): ArrayList<String> {
            val remainingWords = ArrayList<String>()
            val startX = userGesture.getFirstX()
            val startY = userGesture.getFirstY()
            val endX = userGesture.getLastX()
            val endY = userGesture.getLastY()
            val startKeys = findNClosestKeys(startX, startY, 2, keys)
            val endKeys = findNClosestKeys(endX, endY, 2, keys)
            for (startKey in startKeys) {
                for (endKey in endKeys) {
                    val keyPair = Pair(startKey, endKey)
                    val wordsForKeys = synchronized(wordTree) { wordTree[keyPair] }
                    if (wordsForKeys != null) {
                        remainingWords.addAll(wordsForKeys)
                    }
                }
            }
            return remainingWords
        }

        /**
         * Finds the words whose ideal gesture length is within a certain threshold of the user
         * gesture's length.
         *
         * @param userGesture The current user gesture.
         * @param words A list of words to consider.
         * @return A list of words that remained after pruning the input list by length.
         */
        fun pruneByLength(
            userGesture: Gesture,
            words: ArrayList<String>,
            keysByCharacter: SparseArrayCompat<TextKey>,
            keys: List<TextKey>,
        ): ArrayList<String> {
            val remainingWords = ArrayList<String>()

            val key = keys.firstOrNull() ?: return arrayListOf()
            val radius = min(key.visibleBounds.height, key.visibleBounds.width)
            val userLength = userGesture.getLength()
            for (word in words) {
                val idealGestures = Gesture.generateIdealGestures(word, keysByCharacter)
                for (idealGesture in idealGestures) {
                    val wordIdealLength = getCachedIdealLength(word, idealGesture)
                    if (abs(userLength - wordIdealLength) < lengthThreshold * radius) {
                        remainingWords.add(word)
                    }
                }
            }
            return remainingWords
        }

        private val cachedIdealLength = ConcurrentHashMap<String, Float>()
        private fun getCachedIdealLength(word: String, idealGesture: Gesture): Float {
            return cachedIdealLength.getOrPut(word) { idealGesture.getLength() }
        }

        companion object {
            private fun getFirstKeyLastKey(
                word: String,
                keysByCharacter: SparseArrayCompat<TextKey>,
            ): Pair<Int, Int>? {
                val firstLetter = word[0]
                val lastLetter = word[word.length - 1]
                val firstBaseChar = Normalizer.normalize(firstLetter.toString(), Normalizer.Form.NFD)[0]
                val lastBaseChar = Normalizer.normalize(lastLetter.toString(), Normalizer.Form.NFD)[0]
                return when {
                    keysByCharacter.indexOfKey(firstBaseChar.code) < 0 || keysByCharacter.indexOfKey(lastBaseChar.code) < 0 -> {
                        null
                    }
                    else -> {
                        val firstKey = keysByCharacter[firstBaseChar.code]
                        val lastKey = keysByCharacter[lastBaseChar.code]
                        if (firstKey != null && lastKey != null) {
                            firstKey.baseCode() to lastKey.baseCode()
                        } else {
                            null
                        }
                    }
                }
            }

            /**
             * Finds a chosen number of keys closest to a given point on the keyboard.
             *
             * @param x X coordinate of the point.
             * @param y Y coordinate of the point.
             * @param n The number of keys to return.
             * @param keys The keys of the keyboard.
             * @return A list of the n closest keys.
             */
            private fun findNClosestKeys(
                x: Float, y: Float, n: Int, keys: Iterable<TextKey>
            ): Iterable<Int> {
                val keyDistances = HashMap<TextKey, Float>()
                for (key in keys) {
                    val visibleBoundsCenter = key.visibleBounds.center
                    val distance = Gesture.distance(
                        visibleBoundsCenter.x,
                        visibleBoundsCenter.y,
                        x,
                        y
                    )
                    keyDistances[key] = distance
                }

                return keyDistances.entries.sortedWith { c1, c2 -> c1.value.compareTo(c2.value) }.take(n)
                    .map { it.key.baseCode() }
            }
        }

        init {
            synchronized(wordTree) {
                for (word in words) {
                    val keyPair = getFirstKeyLastKey(word, keysByCharacter)
                    keyPair?.let {
                        wordTree.getOrPut(keyPair) { arrayListOf() }.add(word)
                    }
                }
            }
        }
    }

    class Gesture(
        private val xs: FloatArray = FloatArray(MAX_SIZE),
        private val ys: FloatArray = FloatArray(MAX_SIZE),
        private var size: Int = 0,
    ) {
        companion object {
            // TODO: Find out optimal max size
            private const val MAX_SIZE = 500

            fun generateIdealGestures(word: String, keysByCharacter: SparseArrayCompat<TextKey>): List<Gesture> {
                val idealGesture = Gesture()
                val idealGestureWithLoops = Gesture()
                var previousLetter = '\u0000'
                var hasLoops = false

                // Add points for each key
                for (c in word) {
                    val lc = Character.toLowerCase(c)
                    var key = keysByCharacter[lc.code]
                    if (key == null) {
                        // Try finding the base character instead, e.g., the "e" key instead of "é"
                        val baseCharacter: Char = Normalizer.normalize(lc.toString(), Normalizer.Form.NFD)[0]
                        key = keysByCharacter[baseCharacter.code]
                        if (key == null) {
                            continue
                        }
                    }
                    val visibleBoundsCenter = key.visibleBounds.center

                    // We adda little loop on  the key for duplicate letters
                    // so that we can differentiate words like pool and poll, lull and lul, etc...
                    if (previousLetter == lc) {
                        // bottom right
                        idealGestureWithLoops.addPoint(
                            visibleBoundsCenter.x + key.visibleBounds.width / 4.0f,
                            visibleBoundsCenter.y + key.visibleBounds.height / 4.0f
                        )
                        // top right
                        idealGestureWithLoops.addPoint(
                            visibleBoundsCenter.x + key.visibleBounds.width / 4.0f,
                            visibleBoundsCenter.y - key.visibleBounds.height / 4.0f
                        )
                        // top left
                        idealGestureWithLoops.addPoint(
                            visibleBoundsCenter.x - key.visibleBounds.width / 4.0f,
                            visibleBoundsCenter.y - key.visibleBounds.height / 4.0f
                        )
                        // bottom left
                        idealGestureWithLoops.addPoint(
                            visibleBoundsCenter.x - key.visibleBounds.width / 4.0f,
                            visibleBoundsCenter.y + key.visibleBounds.height / 4.0f
                        )
                        hasLoops = true

                        idealGesture.addPoint(
                            visibleBoundsCenter.x,
                            visibleBoundsCenter.y
                        )
                    } else {
                        idealGesture.addPoint(
                            visibleBoundsCenter.x,
                            visibleBoundsCenter.y
                        )
                        idealGestureWithLoops.addPoint(
                            visibleBoundsCenter.x,
                            visibleBoundsCenter.y
                        )
                    }
                    previousLetter = lc
                }
                return when (hasLoops) {
                    true -> listOf(idealGesture, idealGestureWithLoops)
                    false -> listOf(idealGesture)
                }
            }

            fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
                return sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))
            }
        }

        val isEmpty: Boolean
            get() = size == 0

        fun addPoint(x: Float, y: Float) {
            if (size >= MAX_SIZE) {
                return
            }
            xs[size] = x
            ys[size] = y
            size += 1
        }

        /**
         * Resamples the gesture into a new gesture with the chosen number of points by oversampling
         * it.
         *
         * @param numPoints The number of points that the new gesture will have. Must be superior to
         * the number of points in the current gesture.
         * @return An oversampled copy of the gesture.
         */
        fun resample(numPoints: Int): Gesture {
            val interpointDistance = (getLength() / numPoints)
            val resampledGesture = Gesture()
            resampledGesture.addPoint(xs[0], ys[0])
            var lastX = xs[0]
            var lastY = ys[0]
            var newX: Float
            var newY: Float
            var cumulativeError = 0.0f

            // otherwise nothing happens if size is only 1:
            if (this.size == 1) {
                for (i in 0 until SAMPLING_POINTS) {
                    resampledGesture.addPoint(xs[0], ys[0])
                }
            }

            for (i in 0 until size - 1) {
                // We calculate the unit vector from the two points we're between in the actual
                // gesture
                var dx = xs[i + 1] - xs[i]
                var dy = ys[i + 1] - ys[i]
                val norm = sqrt(dx.pow(2.0f) + dy.pow(2.0f))
                dx /= norm
                dy /= norm

                // The number of evenly sampled points that fit between the two actual points
                var numNewPoints = norm / interpointDistance

                // The number of point that'd fit between the two actual points is often not round,
                // which means we'll get an increasingly large error as we resample the gesture
                // and round down that number. To compensate for this we keep track of the error
                // and add additional points when it gets too large.
                cumulativeError += numNewPoints - numNewPoints.toInt()
                if (cumulativeError > 1) {
                    numNewPoints = (numNewPoints.toInt() + cumulativeError.toInt()).toFloat()
                    cumulativeError %= 1
                }
                for (j in 0 until numNewPoints.toInt()) {
                    newX = lastX + dx * interpointDistance
                    newY = lastY + dy * interpointDistance
                    lastX = newX
                    lastY = newY
                    resampledGesture.addPoint(newX, newY)
                }
            }
            return resampledGesture
        }

        fun normalizeByBoxSide(): Gesture {
            val normalizedGesture = Gesture()

            var maxX = -1.0f
            var maxY = -1.0f
            var minX = 10000.0f
            var minY = 10000.0f

            for (i in 0 until size) {
                maxX = max(xs[i], maxX)
                maxY = max(ys[i], maxY)
                minX = min(xs[i], minX)
                minY = min(ys[i], minY)
            }

            val width = maxX - minX
            val height = maxY - minY
            val longestSide = max(max(width, height), 0.00001f)

            val centroidX = (width / 2 + minX) / longestSide
            val centroidY = (height / 2 + minY) / longestSide

            for (i in 0 until size) {
                val x = xs[i] / longestSide - centroidX
                val y = ys[i] / longestSide - centroidY
                normalizedGesture.addPoint(x, y)
            }

            return normalizedGesture
        }

        fun getFirstX(): Float = xs.getOrElse(0) { 0f }
        fun getFirstY(): Float = ys.getOrElse(0) { 0f }
        fun getLastX(): Float = xs.getOrElse(size - 1) { 0f }
        fun getLastY(): Float = ys.getOrElse(size - 1) { 0f }

        fun getLength(): Float {
            var length = 0f
            for (i in 1 until size) {
                val previousX = xs[i - 1]
                val previousY = ys[i - 1]
                val currentX = xs[i]
                val currentY = ys[i]
                length += distance(previousX, previousY, currentX, currentY)
            }

            return length
        }

        fun clear() {
            this.size = 0
        }

        fun getX(i: Int): Float = xs.getOrElse(i) { 0f }
        fun getY(i: Int): Float = ys.getOrElse(i) { 0f }

        fun clone(): Gesture {
            return Gesture(xs.clone(), ys.clone(), size)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Gesture

            if (this.size != other.size) return false

            for (i in 0 until size) {
                if (xs[i] != other.xs[i] || ys[i] != other.ys[i]) return false
            }

            return true
        }

        override fun hashCode(): Int {
            var result = xs.contentHashCode()
            result = 31 * result + ys.contentHashCode()
            result = 31 * result + size
            return result
        }
    }
}
