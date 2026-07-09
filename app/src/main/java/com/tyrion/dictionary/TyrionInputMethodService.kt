package com.tyrion.dictionary

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView

/**
 * TyrionDictionary IME — reads the phone's physical number keys directly
 * (KeyEvent.KEYCODE_0..9, STAR, POUND, DEL) and predicts Filipino words, T9-style.
 *
 * Design choices:
 * - No keyboard grid is ever shown. The only on-screen element is a single-line
 *   status badge (e.g. "Abc • T9") so the current case/prediction mode is always
 *   visible without needing to pull down a notification shade.
 * - The predicted word itself is shown live INSIDE the text field ("composing text"),
 *   not in any suggestion list.
 * - Only keys we actually use are consumed. Everything else (including backspace when
 *   there's nothing to delete) is passed through untouched, so system Back behavior and
 *   any accessibility service watching key events still get a chance to see it.
 *
 * Controls:
 * - 2-9: T9 predictive letters (or manual multi-tap letters when predictive is off)
 * - 0: space (confirms the current word)
 * - 1: punctuation cycle (. , ? ! ' - @)
 * - *: cycle word suggestion if mid-word, otherwise cycle case mode (Abc/ABC/abc/123)
 * - #: confirm word if mid-word; otherwise toggle predictive T9 on/off (or "." in 123 mode)
 * - DEL: backspace (only consumed if there's actually something to delete)
 * - ENTER: confirm word + editor action
 */
class TyrionInputMethodService : InputMethodService() {

    private enum class Mode { PROPER, CAPS, LOWER, NUMBER }

    private val modeCycle = listOf(Mode.PROPER, Mode.CAPS, Mode.LOWER, Mode.NUMBER)
    private var mode = Mode.PROPER
    private var predictiveEnabled = true

    // --- Predictive (T9) state ---
    private val currentDigits = StringBuilder()
    private var candidateIndex = 0

    // --- Manual multi-tap state (used when predictiveEnabled == false) ---
    private var lastMultitapKeyCode = -1
    private var lastMultitapTime = 0L
    private var multitapIndex = 0
    private var isWordStart = true
    private val multitapTimeoutMs = 900L

    // --- Punctuation cycling (shared by both modes) ---
    private var punctCycleIndex = -1
    private var lastActionWasPunct = false
    private val punctuations = listOf(".", ",", "?", "!", "'", "-", "@")

    private lateinit var textMode: TextView

    override fun onCreate() {
        super.onCreate()
        T9Dictionary.ensureLoaded(applicationContext)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_mode_view, null)
        textMode = view.findViewById(R.id.imeMode)
        refreshModeIndicator()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetWordState()
        refreshModeIndicator()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Commit any half-typed word so it isn't silently lost when switching away.
        if (currentDigits.isNotEmpty()) {
            commitCurrentWord(appendSpace = false)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("TyrionIME", "onKeyDown keyCode=$keyCode mode=$mode predictive=$predictiveEnabled")
        val digitChar = digitCharFor(keyCode)

        // --- Numeric mode: keys type literal digits, nothing is predicted ---
        if (mode == Mode.NUMBER && digitChar != null) {
            currentInputConnection?.commitText(digitChar.toString(), 1)
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9 -> {
                if (predictiveEnabled) onDigitPressed(digitChar!!) else onMultitapDigit(keyCode, digitChar!!)
                true
            }
            KeyEvent.KEYCODE_0 -> {
                if (predictiveEnabled) {
                    commitCurrentWord(appendSpace = true)
                } else {
                    currentInputConnection?.commitText(" ", 1)
                    lastMultitapKeyCode = -1
                    isWordStart = true
                }
                true
            }
            KeyEvent.KEYCODE_1 -> {
                onOnePressed()
                true
            }
            KeyEvent.KEYCODE_STAR -> {
                if (predictiveEnabled && currentDigits.isNotEmpty()) {
                    cycleSuggestion()
                } else {
                    cycleMode()
                }
                true
            }
            KeyEvent.KEYCODE_POUND -> {
                when {
                    mode == Mode.NUMBER -> currentInputConnection?.commitText(".", 1)
                    predictiveEnabled && currentDigits.isNotEmpty() -> commitCurrentWord(appendSpace = false)
                    else -> togglePredictive()
                }
                true
            }
            KeyEvent.KEYCODE_DEL -> onBackspace()
            KeyEvent.KEYCODE_ENTER -> {
                if (predictiveEnabled && currentDigits.isNotEmpty()) {
                    commitCurrentWord(appendSpace = false)
                }
                sendDefaultEditorAction(true)
                true
            }
            // Deliberately NOT intercepting KEYCODE_DPAD_CENTER, DPAD_UP/DOWN/LEFT/RIGHT,
            // or KEYCODE_BACK: leaving these untouched keeps accessibility/cursor tools
            // and normal navigation working while a text field is focused.
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun digitCharFor(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_0 -> '0'
        KeyEvent.KEYCODE_1 -> '1'
        KeyEvent.KEYCODE_2 -> '2'
        KeyEvent.KEYCODE_3 -> '3'
        KeyEvent.KEYCODE_4 -> '4'
        KeyEvent.KEYCODE_5 -> '5'
        KeyEvent.KEYCODE_6 -> '6'
        KeyEvent.KEYCODE_7 -> '7'
        KeyEvent.KEYCODE_8 -> '8'
        KeyEvent.KEYCODE_9 -> '9'
        else -> null
    }

    // --- Mode / predictive toggling ---

    private fun cycleMode() {
        val idx = (modeCycle.indexOf(mode) + 1) % modeCycle.size
        mode = modeCycle[idx]
        currentDigits.clear()
        candidateIndex = 0
        lastMultitapKeyCode = -1
        refreshModeIndicator()
    }

    private fun togglePredictive() {
        predictiveEnabled = !predictiveEnabled
        currentDigits.clear()
        candidateIndex = 0
        lastMultitapKeyCode = -1
        refreshModeIndicator()
    }

    private fun applyCase(word: String): String = when (mode) {
        Mode.LOWER -> word.lowercase()
        Mode.CAPS -> word.uppercase()
        Mode.PROPER -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        Mode.NUMBER -> word
    }

    private fun casedChar(ch: Char): String = when (mode) {
        Mode.CAPS -> ch.uppercase()
        Mode.LOWER -> ch.lowercase()
        Mode.PROPER -> if (isWordStart) ch.uppercase() else ch.lowercase()
        Mode.NUMBER -> ch.toString()
    }

    private fun caseLabel(): String = when (mode) {
        Mode.PROPER -> "Abc"
        Mode.CAPS -> "ABC"
        Mode.LOWER -> "abc"
        Mode.NUMBER -> "123"
    }

    /** Turns a T9 digit code with no dictionary match into letters instead of raw numbers,
     *  e.g. "26" (no match) -> "an" (first letter of each key), so the user never sees digits. */
    private fun literalLettersFallback(code: String): String {
        val sb = StringBuilder()
        for (d in code) {
            val letters = T9Dictionary.lettersForDigit(d)
            sb.append(letters?.firstOrNull() ?: d)
        }
        return sb.toString()
    }

    // --- T9 predictive word composition (shown live in the text field) ---

    private fun onDigitPressed(digit: Char) {
        currentDigits.append(digit)
        candidateIndex = 0
        lastActionWasPunct = false
        updateComposing()
    }

    private fun cycleSuggestion() {
        val candidates = T9Dictionary.candidatesFor(currentDigits.toString())
        if (candidates != null && candidates.isNotEmpty()) {
            candidateIndex = (candidateIndex + 1) % candidates.size
            updateComposing()
        }
    }

    private fun bestGuessFor(code: String): String {
        val candidates = T9Dictionary.candidatesFor(code)
        return if (candidates != null && candidates.isNotEmpty()) {
            applyCase(candidates[candidateIndex % candidates.size])
        } else {
            applyCase(literalLettersFallback(code))
        }
    }

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        ic.setComposingText(bestGuessFor(currentDigits.toString()), 1)
    }

    private fun commitCurrentWord(appendSpace: Boolean) {
        val ic = currentInputConnection
        val code = currentDigits.toString()
        if (code.isNotEmpty()) {
            ic?.commitText(bestGuessFor(code) + if (appendSpace) " " else "", 1)
        } else if (appendSpace) {
            ic?.commitText(" ", 1)
        }
        currentDigits.clear()
        candidateIndex = 0
        lastActionWasPunct = false
    }

    // --- Manual multi-tap word composition (used when predictive is OFF) ---

    private fun onMultitapDigit(keyCode: Int, digit: Char) {
        val letters = T9Dictionary.lettersForDigit(digit) ?: return
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()

        if (keyCode == lastMultitapKeyCode && now - lastMultitapTime < multitapTimeoutMs) {
            multitapIndex = (multitapIndex + 1) % letters.length
            ic.deleteSurroundingText(1, 0)
        } else {
            multitapIndex = 0
        }

        ic.commitText(casedChar(letters[multitapIndex]), 1)
        lastMultitapKeyCode = keyCode
        lastMultitapTime = now
        isWordStart = false
        lastActionWasPunct = false
    }

    // --- Shared: punctuation cycling and backspace ---

    private fun onOnePressed() {
        if (predictiveEnabled && currentDigits.isNotEmpty()) {
            commitCurrentWord(appendSpace = false)
        }
        val ic = currentInputConnection ?: return
        if (lastActionWasPunct) {
            ic.deleteSurroundingText(1, 0)
            punctCycleIndex = (punctCycleIndex + 1) % punctuations.size
        } else {
            punctCycleIndex = 0
        }
        ic.commitText(punctuations[punctCycleIndex], 1)
        lastActionWasPunct = true
        lastMultitapKeyCode = -1
        isWordStart = true
    }

    /** Returns true only if we actually consumed the key (i.e. there was something to delete). */
    private fun onBackspace(): Boolean {
        if (predictiveEnabled && currentDigits.isNotEmpty()) {
            currentDigits.deleteCharAt(currentDigits.length - 1)
            candidateIndex = 0
            if (currentDigits.isEmpty()) {
                currentInputConnection?.commitText("", 1) // clears the composing span
            } else {
                updateComposing()
            }
            lastActionWasPunct = false
            return true
        }

        lastActionWasPunct = false
        lastMultitapKeyCode = -1
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        return if (!before.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
            true
        } else {
            // Nothing to delete: don't consume the key, so it can act as Back / propagate normally.
            false
        }
    }

    private fun resetWordState() {
        currentDigits.clear()
        candidateIndex = 0
        punctCycleIndex = -1
        lastActionWasPunct = false
        lastMultitapKeyCode = -1
        isWordStart = true
    }

    // --- On-screen mode badge (single line, always visible while typing) ---

    private fun refreshModeIndicator() {
        if (!::textMode.isInitialized) return
        textMode.text = if (mode == Mode.NUMBER) {
            "123"
        } else {
            "${caseLabel()} • ${if (predictiveEnabled) "T9" else "MULTI"}"
        }
    }
}
