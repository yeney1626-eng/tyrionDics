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
 * The predicted word is shown live INSIDE the text field itself (as Android
 * "composing text", the standard underlined-preview mechanic), not in a
 * separate suggestion bar. Only a tiny mode indicator (abc / Abc / ABC / 123)
 * is shown, since that state isn't otherwise visible.
 *
 * Mode toggle: press * while NOT in the middle of a word to cycle
 * Proper -> CAPS -> lower -> 123 -> Proper ...
 * (While a word is being composed, * instead cycles suggestions for that word.)
 */
class TyrionInputMethodService : InputMethodService() {

    private enum class Mode { PROPER, CAPS, LOWER, NUMBER }

    private val modeCycle = listOf(Mode.PROPER, Mode.CAPS, Mode.LOWER, Mode.NUMBER)
    private var mode = Mode.PROPER

    private val currentDigits = StringBuilder()
    private var candidateIndex = 0

    private var punctCycleIndex = -1
    private var lastActionWasPunct = false
    private val punctuations = listOf(".", ",", "?", "!", "'", "-", "@")

    private lateinit var textMode: TextView

    override fun onCreate() {
        super.onCreate()
        T9Dictionary.ensureLoaded(applicationContext)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_candidates_view, null)
        textMode = view.findViewById(R.id.imeMode)
        refreshModeIndicator()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetWordState()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Commit any half-typed word so it isn't silently lost when switching away.
        if (currentDigits.isNotEmpty()) {
            commitCurrentWord(appendSpace = false)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("TyrionIME", "onKeyDown keyCode=$keyCode mode=$mode")
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
                onDigitPressed(digitChar!!)
                true
            }
            KeyEvent.KEYCODE_0 -> {
                commitCurrentWord(appendSpace = true)
                true
            }
            KeyEvent.KEYCODE_1 -> {
                onOnePressed()
                true
            }
            KeyEvent.KEYCODE_STAR -> {
                if (currentDigits.isNotEmpty()) {
                    cycleSuggestion()
                } else {
                    cycleMode()
                }
                true
            }
            KeyEvent.KEYCODE_POUND -> {
                if (mode == Mode.NUMBER) {
                    currentInputConnection?.commitText(".", 1)
                } else {
                    commitCurrentWord(appendSpace = false)
                }
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                onBackspace()
                true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (currentDigits.isNotEmpty()) {
                    commitCurrentWord(appendSpace = false)
                }
                sendDefaultEditorAction(true)
                true
            }
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

    // --- Mode handling ---

    private fun cycleMode() {
        val idx = (modeCycle.indexOf(mode) + 1) % modeCycle.size
        mode = modeCycle[idx]
        refreshModeIndicator()
    }

    private fun applyCase(word: String): String = when (mode) {
        Mode.LOWER -> word.lowercase()
        Mode.CAPS -> word.uppercase()
        Mode.PROPER -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        Mode.NUMBER -> word
    }

    private fun refreshModeIndicator() {
        if (!::textMode.isInitialized) return
        textMode.text = when (mode) {
            Mode.PROPER -> "Abc"
            Mode.CAPS -> "ABC"
            Mode.LOWER -> "abc"
            Mode.NUMBER -> "123"
        }
    }

    // --- Word composition (shown live in the text field via composing text) ---

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

    private fun onBackspace() {
        if (currentDigits.isNotEmpty()) {
            currentDigits.deleteCharAt(currentDigits.length - 1)
            candidateIndex = 0
            if (currentDigits.isEmpty()) {
                currentInputConnection?.commitText("", 1) // clears the composing span
            } else {
                updateComposing()
            }
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        lastActionWasPunct = false
    }

    private fun onOnePressed() {
        // Commit any pending word first (without a trailing space), then cycle punctuation.
        if (currentDigits.isNotEmpty()) {
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
    }

    /** Shows the current best-guess word live in the field, underlined, uncommitted. */
    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        val code = currentDigits.toString()
        val candidates = T9Dictionary.candidatesFor(code)
        val display = if (candidates != null && candidates.isNotEmpty()) {
            applyCase(candidates[candidateIndex % candidates.size])
        } else {
            code // no dictionary match yet: show the raw digits being typed
        }
        ic.setComposingText(display, 1)
    }

    private fun commitCurrentWord(appendSpace: Boolean) {
        val ic = currentInputConnection
        val code = currentDigits.toString()
        if (code.isNotEmpty()) {
            val candidates = T9Dictionary.candidatesFor(code)
            val word = if (candidates != null && candidates.isNotEmpty()) {
                applyCase(candidates[candidateIndex % candidates.size])
            } else {
                code
            }
            ic?.commitText(word + if (appendSpace) " " else "", 1)
        } else if (appendSpace) {
            ic?.commitText(" ", 1)
        }
        currentDigits.clear()
        candidateIndex = 0
        lastActionWasPunct = false
    }

    private fun resetWordState() {
        currentDigits.clear()
        candidateIndex = 0
        punctCycleIndex = -1
        lastActionWasPunct = false
        refreshModeIndicator()
    }
}
