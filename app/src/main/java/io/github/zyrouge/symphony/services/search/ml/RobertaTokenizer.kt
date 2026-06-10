package io.github.zyrouge.symphony.services.search.ml

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * RoBERTa BPE Tokenizer — پیاده‌سازی خالصِ کاتلین.
 * فایل‌های vocab.json و merges.txt را از assets بارگذاری می‌کند
 * و متن ورودی را دقیقاً مطابق با خروجی HuggingFace tokenize می‌کند.
 *
 * توکن‌های ویژه RoBERTa:
 *   <s> = 0 (BOS/CLS)
 *   </s> = 2 (EOS/SEP)
 *   <pad> = 1
 */
class RobertaTokenizer(context: Context) {

    // --- Token IDs ثابت ---
    private val bosTokenId = 0L  // <s>
    private val eosTokenId = 2L  // </s>
    private val padTokenId = 1L  // <pad>

    // حداکثر طول توکن‌ها (مطابق با config مدل CLAP)
    private val maxLength = 77

    // نگاشت واژه → شناسه عددی
    private val vocab: Map<String, Long>
    // لیست مرتب‌شده‌ی جفت‌های ادغام BPE
    private val merges: List<Pair<String, String>>
    // کش برای سرعت بالاتر
    private val bpeCache = mutableMapOf<String, List<String>>()

    // جدول تبدیل بایت → یونیکد (مطابق با GPT-2/RoBERTa)
    private val byteEncoder: Map<Int, Char>

    init {
        // --- ۱. بارگذاری vocab.json ---
        val vocabJson = context.assets.open("vocab.json").bufferedReader().readText()
        val vocabObj = JSONObject(vocabJson)
        val tempVocab = mutableMapOf<String, Long>()
        for (key in vocabObj.keys()) {
            tempVocab[key] = vocabObj.getLong(key)
        }
        vocab = tempVocab

        // --- ۲. بارگذاری merges.txt ---
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

        // --- ۳. ساخت جدول بایت → یونیکد ---
        byteEncoder = buildByteEncoder()
    }

    /**
     * Tokenize + Encode متن ورودی.
     * خروجی: Pair<LongArray, LongArray> شامل (inputIds, attentionMask)
     * - طول ثابت = maxLength (77)
     * - قالب: [<s>, ...tokens..., </s>, <pad>, <pad>, ...]
     */
    fun encode(rawText: String): Pair<LongArray, LongArray> {
        // RoBERTa expects the first word to have a leading space to map to the 'Ġ' (whole word) token.
        val text = if (rawText.startsWith(" ")) rawText else " $rawText"
        val tokens = tokenize(text)

        // تبدیل توکن‌ها به ID + اضافه کردن توکن‌های ویژه
        val tokenIds = mutableListOf(bosTokenId)
        for (token in tokens) {
            val id = vocab[token]
            if (id != null) {
                tokenIds.add(id)
            }
            // اگر توکنی در vocab نبود، نادیده گرفته می‌شود (مثل <unk>)
        }
        tokenIds.add(eosTokenId)

        // برش (truncate) در صورت بلند بودن
        val truncated = if (tokenIds.size > maxLength) {
            tokenIds.subList(0, maxLength - 1).toMutableList().also { it.add(eosTokenId) }
        } else {
            tokenIds
        }

        // ساخت آرایه‌های نهایی با padding
        val inputIds = LongArray(maxLength) { padTokenId }
        val attentionMask = LongArray(maxLength) { 0L }

        for (i in truncated.indices) {
            inputIds[i] = truncated[i]
            attentionMask[i] = 1L
        }

        return inputIds to attentionMask
    }

    /**
     * مرحله اصلی: تبدیل متن به لیست توکن‌های BPE.
     */
    private fun tokenize(text: String): List<String> {
        // تقسیم متن به کلمات (مطابق الگوی RoBERTa/GPT-2)
        val pattern = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+""")
        val words = pattern.findAll(text).map { it.value }.toList()

        val allTokens = mutableListOf<String>()
        for (word in words) {
            // تبدیل هر بایت کلمه به کاراکتر یونیکد (byte-level encoding)
            val encoded = word.toByteArray(Charsets.UTF_8).map { b ->
                byteEncoder[b.toInt() and 0xFF] ?: '?'
            }.joinToString("")

            val bpeTokens = bpe(encoded)
            allTokens.addAll(bpeTokens)
        }
        return allTokens
    }

    /**
     * الگوریتم BPE (Byte-Pair Encoding):
     * به صورت تکراری پرتکرارترین جفت کاراکترها را ادغام می‌کند
     * تا به توکن‌های نهایی برسد.
     */
    private fun bpe(token: String): List<String> {
        bpeCache[token]?.let { return it }

        var word = token.map { it.toString() }.toMutableList()
        if (word.size <= 1) {
            bpeCache[token] = word
            return word
        }

        while (true) {
            // پیدا کردن بهترین جفت (با کمترین اندیس در لیست merges)
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

            // ادغام جفت در کلمه
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
     * ساخت جدول تبدیل byte → unicode character
     * (دقیقاً مطابق با bytes_to_unicode در GPT-2/RoBERTa)
     */
    private fun buildByteEncoder(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        val cs = mutableListOf<Int>()

        // محدوده‌های اصلی ASCII قابل چاپ
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
