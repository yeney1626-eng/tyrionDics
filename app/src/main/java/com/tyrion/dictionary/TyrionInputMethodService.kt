package com.tyrion.dictionary

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

/**
 * TyrionDictionary IME — reads the phone's physical number keys directly
 * (KeyEvent.KEYCODE_1..9, STAR, POUND, DEL) and predicts Filipino words, T9-style.
 * No on-screen keyboard grid is drawn; only a small suggestion strip is shown,
 * since typing happens on the device's real hardware keypad.
 */
class TyrionInputMethodService : InputMethodService() {

    private val currentDigits = StringBuilder()
    private var candidateIndex = 0

    private var punctCycleIndex = -1
    private var lastActionWasPunct = false
    private val punctuations = listOf(".", ",", "?", "!", "'", "-", "@")

    private lateinit var textSuggestion: TextView
    private lateinit var textCandidateCount: TextView

    override fun onCreate() {
        super.onCreate()
        T9Dictionary.ensureLoaded(applicationContext)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_candidates_view, null)
        textSuggestion = view.findViewById(R.id.imeSuggestion)
        textCandidateCount = view.findViewById(R.id.imeCandidateCount)
        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
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
        Log.d("TyrionIME", "onKeyDown keyCode=$keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9 -> {
                onDigitPressed(digitFor(keyCode))
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
                cycleSuggestion()
                true
            }
            KeyEvent.KEYCODE_POUND -> {
                commitCurrentWord(appendSpace = false)
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

    private fun digitFor(keyCode: Int): Char = when (keyCode) {
        KeyEvent.KEYCODE_2 -> '2'
        KeyEvent.KEYCODE_3 -> '3'
        KeyEvent.KEYCODE_4 -> '4'
        KeyEvent.KEYCODE_5 -> '5'
        KeyEvent.KEYCODE_6 -> '6'
        KeyEvent.KEYCODE_7 -> '7'
        KeyEvent.KEYCODE_8 -> '8'
        KeyEvent.KEYCODE_9 -> '9'
        else -> '0'
    }

    private fun onDigitPressed(digit: Char) {
        currentDigits.append(digit)
        candidateIndex = 0
        lastActionWasPunct = false
        refreshSuggestion()
    }

    private fun cycleSuggestion() {
        val candidates = T9Dictionary.candidatesFor(currentDigits.toString())
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

    private fun commitCurrentWord(appendSpace: Boolean) {
        val ic = currentInputConnection
        val code = currentDigits.toString()
        if (code.isNotEmpty()) {
            val candidates = T9Dictionary.candidatesFor(code)
            val word = if (candidates != null && candidates.isNotEmpty()) {
                candidates[candidateIndex % candidates.size]
            } else {
                code
            }
            ic?.commitText(word, 1)
        }
        if (appendSpace) {
            ic?.commitText(" ", 1)
        }
        currentDigits.clear()
        candidateIndex = 0
        lastActionWasPunct = false
        refreshSuggestion()
    }

    private fun resetWordState() {
        currentDigits.clear()
        candidateIndex = 0
        punctCycleIndex = -1
        lastActionWasPunct = false
        refreshSuggestion()
    }

    private fun refreshSuggestion() {
        if (!::textSuggestion.isInitialized) return
        val code = currentDigits.toString()
        if (code.isEmpty()) {
            textSuggestion.text = getString(R.string.hint_empty)
            textCandidateCount.text = ""
            return
        }
        val candidates = T9Dictionary.candidatesFor(code)
        if (candidates != null && candidates.isNotEmpty()) {
            textSuggestion.text = candidates[candidateIndex % candidates.size]
            textCandidateCount.text = if (candidates.size > 1) "${candidateIndex + 1}/${candidates.size}" else ""
        } else {
            textSuggestion.text = code
            textCandidateCount.text = "walang tugma"
        }
    }
}
