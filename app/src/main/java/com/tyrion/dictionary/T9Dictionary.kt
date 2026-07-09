package com.tyrion.dictionary

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the Filipino word list once and indexes every word by its T9 digit code
 * (e.g. "bahay" -> "22429"), so key presses can be matched to candidate words instantly.
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

    private val index = HashMap<String, MutableList<String>>()
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        try {
            val reader = BufferedReader(
                InputStreamReader(context.assets.open("dictionary_fil.txt"), Charsets.UTF_8)
            )
            reader.useLines { lines ->
                for (line in lines) {
                    val word = line.trim()
                    if (word.isEmpty()) continue
                    val code = wordToT9(word) ?: continue
                    index.getOrPut(code) { mutableListOf() }.add(word)
                }
            }
            for (list in index.values) {
                list.sortWith(compareBy({ it.length }, { it }))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        loaded = true
    }

    fun candidatesFor(code: String): List<String>? = index[code]

    /** Letters assigned to a given digit key, e.g. lettersForDigit('2') -> "abc". Used by manual multi-tap mode. */
    fun lettersForDigit(digit: Char): String? = keyLetters[digit]

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
