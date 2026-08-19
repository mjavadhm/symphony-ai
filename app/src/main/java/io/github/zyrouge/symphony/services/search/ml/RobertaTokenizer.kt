package io.github.zyrouge.symphony.services.search.ml

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * RoBERTa BPE tokenizer — a pure Kotlin implementation.
 * Loads vocab.json and merges.txt from assets and tokenizes the input text so the
 * result matches HuggingFace's output exactly.
 *
 * RoBERTa special tokens:
 *   <s> = 0 (BOS/CLS)
 *   </s> = 2 (EOS/SEP)
 *   <pad> = 1
 */
class RobertaTokenizer(context: Context) {

    // --- Fixed token IDs ---
    private val bosTokenId = 0L  // <s>
    private val eosTokenId = 2L  // </s>
    private val padTokenId = 1L  // <pad>

    // Maximum token length (matches the CLAP model config)
    private val maxLength = 77

    // Word → numeric id mapping
    private val vocab: Map<String, Long>
    // Ordered list of BPE merge pairs
    private val merges: List<Pair<String, String>>
    // Cache for extra speed
    private val bpeCache = mutableMapOf<String, List<String>>()

    // Byte → unicode conversion table (matching GPT-2/RoBERTa)
    private val byteEncoder: Map<Int, Char>

    init {
        // --- 1. Load vocab.json ---
        val vocabJson = context.assets.open("vocab.json").bufferedReader().readText()
        val vocabObj = JSONObject(vocabJson)
        val tempVocab = mutableMapOf<String, Long>()
        for (key in vocabObj.keys()) {
            tempVocab[key] = vocabObj.getLong(key)
        }
        vocab = tempVocab

        // --- 2. Load merges.txt ---
        val mergesList = mutableListOf<Pair<String, String>>()
        val reader = BufferedReader(InputStreamReader(context.assets.open("merges.txt")))
        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("#") || line.isBlank()) return@forEach
                val parts = line.split(" ")
                if (parts.size == 2) {
                    mergesList.add(parts[0] to parts[1])
                }
            }
        }
        merges = mergesList

        // --- 3. Build the byte → unicode table ---
        byteEncoder = buildByteEncoder()
    }

    /**
     * Tokenizes and encodes the input text.
     * Returns a Pair<LongArray, LongArray> holding (inputIds, attentionMask)
     * - fixed length = maxLength (77)
     * - layout: [<s>, ...tokens..., </s>, <pad>, <pad>, ...]
     */
    fun encode(rawText: String): Pair<LongArray, LongArray> {
        // RoBERTa expects the first word to have a leading space to map to the 'Ġ' (whole word) token.
        val text = if (rawText.startsWith(" ")) rawText else " $rawText"
        val tokens = tokenize(text)

        // Convert tokens to ids and add the special tokens
        val tokenIds = mutableListOf(bosTokenId)
        for (token in tokens) {
            val id = vocab[token]
            if (id != null) {
                tokenIds.add(id)
            }
            // Tokens that aren't in the vocab are simply skipped (like <unk>)
        }
        tokenIds.add(eosTokenId)

        // Truncate when the sequence is too long
        val truncated = if (tokenIds.size > maxLength) {
            tokenIds.subList(0, maxLength - 1).toMutableList().also { it.add(eosTokenId) }
        } else {
            tokenIds
        }

        // Build the final arrays with padding
        val inputIds = LongArray(maxLength) { padTokenId }
        val attentionMask = LongArray(maxLength) { 0L }

        for (i in truncated.indices) {
            inputIds[i] = truncated[i]
            attentionMask[i] = 1L
        }

        return inputIds to attentionMask
    }

    /**
     * The main stage: turns text into a list of BPE tokens.
     */
    private fun tokenize(text: String): List<String> {
        // Split the text into words (following the RoBERTa/GPT-2 pattern)
        val pattern = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+""")
        val words = pattern.findAll(text).map { it.value }.toList()

        val allTokens = mutableListOf<String>()
        for (word in words) {
            // Turn every byte of the word into a unicode character (byte-level encoding)
            val encoded = word.toByteArray(Charsets.UTF_8).map { b ->
                byteEncoder[b.toInt() and 0xFF] ?: '?'
            }.joinToString("")

            val bpeTokens = bpe(encoded)
            allTokens.addAll(bpeTokens)
        }
        return allTokens
    }

    /**
     * The BPE (Byte-Pair Encoding) algorithm:
     * repeatedly merges the highest-priority character pair until only the final
     * tokens remain.
     */
    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }

        var word = token.map { it.toString() }.toMutableList()
        if (word.size <= 1) {
            bpeCache[token] = word
            return word
        }

        while (true) {
            // Find the best pair (the one with the lowest index in the merges list)
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE

            for (i in 0 until word.size - 1) {
                val pair = word[i] to word[i + 1]
                val rank = merges.indexOf(pair)
                if (rank != -1 && rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }

            if (bestPair == null) break

            // Merge that pair inside the word
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == bestPair.first && word[i + 1] == bestPair.second) {
                    newWord.add(bestPair.first + bestPair.second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
        }

        bpeCache[token] = word
        return word
    }

    /**
     * Builds the byte → unicode character conversion table
     * (exactly matching bytes_to_unicode in GPT-2/RoBERTa)
     */
    private fun buildByteEncoder(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        val cs = mutableListOf<Int>()

        // The main printable ASCII ranges
        for (i in '!'.code..'~'.code) { bs.add(i); cs.add(i) }
        for (i in '¡'.code..'¬'.code) { bs.add(i); cs.add(i) }
        for (i in '®'.code..'ÿ'.code) { bs.add(i); cs.add(i) }

        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }

        val result = mutableMapOf<Int, Char>()
        for (i in bs.indices) {
            result[bs[i]] = cs[i].toChar()
        }
        return result
    }
}
