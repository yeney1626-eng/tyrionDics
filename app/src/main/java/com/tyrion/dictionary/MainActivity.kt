package com.tyrion.dictionary

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * TyrionDictionary — a T9-style predictive text keypad for Filipino words.
 * No soft keyboard is ever shown: all input comes from the on-screen numeric keypad.
 */
class MainActivity : AppCompatActivity() {

    // Classic T9 letter mapping
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

    // Maps a digit sequence (T9 code) -> list of matching dictionary words, most common first
    private val t9Index = HashMap<String, MutableList<String>>()

    // Current state
    private var currentDigits = StringBuilder()
    private var candidateIndex = 0
    private val composedText = StringBuilder()

    private lateinit var textComposed: TextView
    private lateinit var textSuggestion: TextView
    private lateinit var textCandidateCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never show the soft keyboard: no EditText requests input focus in this screen.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_main)

        textComposed = findViewById(R.id.textComposed)
        textSuggestion = findViewById(R.id.textSuggestion)
        textCandidateCount = findViewById(R.id.textCandidateCount)

        loadDictionary()
        setupKeypad()
        refreshSuggestion()
    }

    private fun wordToT9(word: String): String? {
        val sb = StringBuilder()
        for (ch in word.lowercase()) {
            val digit = keyLetters.entries.firstOrNull { it.value.contains(ch) }?.key
            if (digit == null) {
                if (ch == 'ñ') {
                    sb.append('6') // group ñ with 'n' (on the 6 key)
                } else {
                    return null // word contains a character we can't map (skip it)
                }
            } else {
                sb.append(digit)
            }
        }
        return sb.toString()
    }

    private fun loadDictionary() {
        try {
            val reader = BufferedReader(InputStreamReader(assets.open("dictionary_fil.txt"), Charsets.UTF_8))
            reader.useLines { lines ->
                for (line in lines) {
                    val word = line.trim()
                    if (word.isEmpty()) continue
                    val code = wordToT9(word) ?: continue
                    val list = t9Index.getOrPut(code) { mutableListOf() }
                    list.add(word)
                }
            }
            // Within each code, prefer shorter/common-looking words first (simple heuristic: shorter first, then alphabetical)
            for (list in t9Index.values) {
                list.sortWith(compareBy({ it.length }, { it }))
            }
        } catch (e: Exception) {
            // If the dictionary fails to load, the app still works as a plain T9 digit pad.
            e.printStackTrace()
        }
    }

    private fun setupKeypad() {
        val digitButtons = mapOf(
            R.id.btn1 to '1',
            R.id.btn2 to '2',
            R.id.btn3 to '3',
            R.id.btn4 to '4',
            R.id.btn5 to '5',
            R.id.btn6 to '6',
            R.id.btn7 to '7',
            R.id.btn8 to '8',
            R.id.btn9 to '9'
        )

        for ((id, digit) in digitButtons) {
            findViewById<Button>(id).setOnClickListener { onDigitPressed(digit) }
        }

        // 0 = space: commits the current suggested word and appends a space
        findViewById<Button>(R.id.btn0).setOnClickListener { commitWord(appendSpace = true) }

        // * = cycle to next suggestion for the current digit sequence
        findViewById<Button>(R.id.btnStar).setOnClickListener { cycleSuggestion() }

        // # = backspace (removes last digit, or last committed character if no digits typed)
        findViewById<Button>(R.id.btnHash).setOnClickListener { onBackspace() }
    }

    private fun onDigitPressed(digit: Char) {
        currentDigits.append(digit)
        candidateIndex = 0
        refreshSuggestion()
    }

    private fun cycleSuggestion() {
        val code = currentDigits.toString()
        val candidates = t9Index[code]
        if (candidates != null && candidates.isNotEmpty()) {
            candidateIndex = (candidateIndex + 1) % candidates.size
            refreshSuggestion()
        }
    }

    private fun onBackspace() {
        if (currentDigits.isNotEmpty()) {
            currentDigits.deleteCharAt(currentDigits.length - 1)
            candidateIndex = 0
            refreshSuggestion()
        } else if (composedText.isNotEmpty()) {
            composedText.deleteCharAt(composedText.length - 1)
            textComposed.text = composedText.toString()
        }
    }

    private fun commitWord(appendSpace: Boolean) {
        val code = currentDigits.toString()
        if (code.isNotEmpty()) {
            val candidates = t9Index[code]
            val word = if (candidates != null && candidates.isNotEmpty()) {
                candidates[candidateIndex % candidates.size]
            } else {
                code // fall back to raw digits if no match found
            }
            composedText.append(word)
        }
        if (appendSpace) {
            composedText.append(' ')
        }
        currentDigits.clear()
        candidateIndex = 0
        textComposed.text = composedText.toString()
        refreshSuggestion()
    }

    private fun refreshSuggestion() {
        val code = currentDigits.toString()
        if (code.isEmpty()) {
            textSuggestion.text = getString(R.string.hint_empty)
            textCandidateCount.text = ""
            return
        }
        val candidates = t9Index[code]
        if (candidates != null && candidates.isNotEmpty()) {
            textSuggestion.text = candidates[candidateIndex % candidates.size]
            textCandidateCount.text = if (candidates.size > 1) "${candidateIndex + 1}/${candidates.size}" else ""
        } else {
            textSuggestion.text = code
            textCandidateCount.text = "walang tugma"
        }
    }
}
