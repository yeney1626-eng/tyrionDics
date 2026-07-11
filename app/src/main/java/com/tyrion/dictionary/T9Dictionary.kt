package com.tyrion.dictionary

import android.content.Context

/**
 * Loads the Filipino word list and indexes every word by its T9 digit code
 * (e.g. "bahay" -> "22429"), so key presses can be matched to candidate words instantly.
 *
 * At ~652k words this file is too large to parse synchronously on the main thread without
 * risking a visible freeze on first keyboard use — so loading happens on a background
 * thread. Reads (candidatesFor) always see either the old index (empty, before the first
 * load finishes) or a fully-built new one, atomically swapped in via a @Volatile reference
 * — never a half-built map, so no locking is needed on the read path.
 */
object T9Dictionary {

    private val keyLetters = mapOf(
        '2' to "abc",
        '3' to "def",
        '4' to "ghi",
        '5' to "jkl",
        '6' to "mno",
        '7' to "pqrs",
        '8' to "tuv",
        '9' to "wxyz"
    )

    @Volatile private var index: Map<String, List<String>> = emptyMap()
    @Volatile private var loadStarted = false

    /** Kicks off loading in the background if it hasn't started yet. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loadStarted) return
        synchronized(this) {
            if (loadStarted) return
            loadStarted = true
            val appContext = context.applicationContext
            Thread({ index = buildIndex(appContext) }, "T9DictionaryLoader").start()
        }
    }

    /** True once the background load has actually finished and candidatesFor() has real data. */
    fun isReady(): Boolean = index.isNotEmpty()

    fun candidatesFor(code: String): List<String>? = index[code]

    /** Letters assigned to a given digit key, e.g. lettersForDigit('2') -> "abc". Used by manual multi-tap mode. */
    fun lettersForDigit(digit: Char): String? = keyLetters[digit]

    private fun buildIndex(context: Context): Map<String, List<String>> {
        val map = HashMap<String, MutableList<String>>()
        try {
            context.assets.open("dictionary_fil.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val word = line.trim()
                    if (word.isEmpty()) continue
                    val code = wordToT9(word) ?: continue
                    map.getOrPut(code) { mutableListOf() }.add(word)
                }
            }
            for (list in map.values) {
                list.sortWith(compareBy({ it.length }, { it }))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun wordToT9(word: String): String? {
        val sb = StringBuilder()
        for (ch in word.lowercase()) {
            if (ch == 'ñ') {
                sb.append('6')
                continue
            }
            val digit = keyLetters.entries.firstOrNull { it.value.contains(ch) }?.key ?: return null
            sb.append(digit)
        }
        return sb.toString()
    }
}
