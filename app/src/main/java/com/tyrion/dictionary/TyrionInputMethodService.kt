package com.tyrion.dictionary

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * TyrionDictionary IME — reads the phone's physical number keys directly
 * (KeyEvent.KEYCODE_0..9, STAR, POUND, DEL) and predicts Filipino words, T9-style.
 *
 * Design choices:
 * - This IME NEVER shows an actual input-method window (onEvaluateInputViewShown = false).
 *   That's deliberate: Android's default behavior is to make the FIRST press of the
 *   physical Back key dismiss any visible IME window rather than let it reach the app
 *   underneath — even for a tiny one-line badge. Showing zero IME window sidesteps that
 *   entirely, so Back always reaches the app on the first press.
 * - The mode/predictive status (e.g. "Abc • T9") is shown via a status bar notification
 *   (not a floating overlay — that used TYPE_APPLICATION_OVERLAY and never actually
 *   rendered on this device/firmware). A notification is the same approach sspanak's TT9
 *   uses, and its changelog specifically documents this fixing visibility/compatibility
 *   issues on Qin F21 Pro-family phones — the same family as this device.
 * - We also broadcast our actual typing start/stop to the ClickyCursor accessibility
 *   app (if installed), since that app used to infer "is a keyboard visible" purely by
 *   scanning accessibility windows — which never saw anything from us once we stopped
 *   showing an IME window. This keeps its cursor-hide/typing-handoff behavior accurate.
 * - The predicted word itself is shown live INSIDE the text field ("composing text"),
 *   not in any suggestion list.
 * - Only keys we actually use are consumed. Everything else (including backspace when
 *   there's nothing to delete, and Back always) is passed through untouched.
 * - Whole-word delete (KEYCODE_DEL only) is implemented standalone here, independent of
 *   whatever ClickyCursor does on its own for the Back key — so it still works correctly
 *   even when ClickyCursor/its accessibility service isn't active.
 *
 * Controls:
 * - 2-9: T9 predictive letters (or manual multi-tap letters when predictive is off)
 * - 0: space (confirms the current word)
 * - 1: punctuation cycle
 * - *: cycle word suggestion if mid-word, otherwise cycle case mode (Abc/ABC/abc/123)
 * - #: confirm word if mid-word; otherwise toggle predictive T9 on/off (or "." in 123 mode)
 * - DEL: backspace — deletes the whole last predicted word in one shot if pressed right
 *   after a commit, otherwise one character (only consumed if there's something to delete)
 * - ENTER: confirm word + editor action
 */
class TyrionInputMethodService : InputMethodService() {

    private enum class Mode { PROPER, CAPS, LOWER, NUMBER }

    companion object {
        // Contract with ClickyCursor: explicit (setPackage-targeted) broadcast telling it
        // whether we're actively composing text right now.
        private const val ACTION_TYPING_STATE = "com.tyrion.dictionary.TYPING_STATE"
        private const val EXTRA_TYPING = "typing"
        private const val EXTRA_STATUS = "status"
        private const val CLICKY_CURSOR_PACKAGE = "com.example.clickycursor"

        private const val NOTIF_CHANNEL_ID = "tyrion_mode_channel"
        private const val NOTIF_ID = 1001
    }

    private val modeCycle = listOf(Mode.PROPER, Mode.CAPS, Mode.LOWER, Mode.NUMBER)
    private var mode = Mode.PROPER
    private var predictiveEnabled = true

    // --- Predictive (T9) state ---
    private val currentDigits = StringBuilder()
    private var candidateIndex = 0

    // Length of the last thing commitCurrentWord() inserted (word + optional space), so the
    // very next backspace can delete the whole predicted word in one shot instead of char by
    // char. Any other action resets this back to 0.
    private var lastCommitLength = 0

    // What we last set as composing text, used to verify the field still matches what we
    // expect — if it doesn't, something external (e.g. an accessibility service editing the
    // field directly) changed it, and our currentDigits buffer needs to be dropped instead of
    // continuing to build predictions on stale, already-gone digits.
    private var composingPreviewText = ""

    // True at the very start of input, and right after sentence-ending punctuation (. ! ?) or
    // Enter — the next Proper-mode word gets capitalized only when this is true, matching
    // normal "auto-capitalize" behavior instead of capitalizing every single word.
    private var isSentenceStart = true

    // --- Manual multi-tap state (used when predictiveEnabled == false) ---
    private var lastMultitapKeyCode = -1
    private var lastMultitapTime = 0L
    private var multitapIndex = 0
    private var isWordStart = true
    private val multitapTimeoutMs = 900L

    // --- Punctuation cycling (shared by both modes) ---
    private var punctCycleIndex = -1
    private var lastActionWasPunct = false
    private val punctuations = listOf(
        ".", ",", "!", "?", "(", ")", "&", "%", "-", "_", ":", ";", "'", "\"", "<", ">", "@", "#"
    )

    override fun onCreate() {
        super.onCreate()
        T9Dictionary.ensureLoaded(applicationContext)
        ensureNotificationChannel()
    }

    // No IME window is ever shown — see the class-level note on why.
    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onCreateInputView(): View = View(this)

    // Deliberately using onStartInput/onFinishInput (not the "...View" variants) — those are
    // tied to the input VIEW's show/hide lifecycle specifically, which fires unreliably (or
    // asymmetrically: start but not finish) when onEvaluateInputViewShown() is always false,
    // as it is here. onStartInput/onFinishInput fire for every editor focus session
    // unconditionally, regardless of whether any view is ever actually shown — which is what
    // was actually causing the status badge to get stuck visible after leaving a field.
    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        resetWordState()
        updateStatusNotification()
        sendTypingState(true)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Commit any half-typed word so it isn't silently lost when switching away.
        if (currentDigits.isNotEmpty()) {
            commitCurrentWord(appendSpace = false)
        }
        cancelStatusNotification()
        sendTypingState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelStatusNotification()
    }

    /**
     * Fires whenever the field's text/cursor changes for ANY reason — including edits made
     * by something else entirely, like an accessibility service's direct text edits, which
     * never go through our onKeyDown at all. When that happens, our internal currentDigits
     * buffer doesn't know a change just occurred, and would otherwise keep building
     * predictions on top of stale, already-changed digits.
     *
     * Checked two ways for robustness: (1) candidatesStart == -1 means the editor reports no
     * composing region even though we think we have one — a good signal on editors that
     * support composing regions properly. (2) directly re-reading the text before the cursor
     * and comparing it to what we last set — this catches the case even when (1) doesn't fire
     * reliably, since it doesn't depend on the editor correctly reporting composing state.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        verifyComposingStillValid(candidatesStart, candidatesEnd)
    }

    /**
     * Confirms our internal currentDigits buffer still matches reality, dropping it if not.
     * Called two ways: (1) passively from onUpdateSelection, which is NOT guaranteed to fire
     * for every kind of external edit — some apps' EditText implementations don't reliably
     * propagate accessibility-originated text changes back through the normal selection-
     * update callback. So it's also called (2) actively, at the very start of every
     * onKeyDown, which re-checks reality directly regardless of whether any callback fired
     * in between. This is what actually closes the "still predicting from deleted digits"
     * bug even when onUpdateSelection alone doesn't catch it.
     */
    private fun verifyComposingStillValid(candidatesStart: Int = -2, candidatesEnd: Int = -2) {
        if (currentDigits.isEmpty()) return
        val ic = currentInputConnection ?: return

        // -2 as a sentinel means "not provided by this caller" (the active/onKeyDown path) —
        // only the passive onUpdateSelection path actually knows this, so only check it there.
        val noComposingRegionReported = candidatesStart == -1 && candidatesEnd == -1

        val actualTextBefore = ic.getTextBeforeCursor(composingPreviewText.length, 0)?.toString() ?: ""
        val textMismatch = actualTextBefore != composingPreviewText

        if (noComposingRegionReported || textMismatch) {
            currentDigits.clear()
            candidateIndex = 0
            lastActionWasPunct = false
            lastCommitLength = 0
            composingPreviewText = ""
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d("TyrionIME", "onKeyDown keyCode=$keyCode mode=$mode predictive=$predictiveEnabled")
        verifyComposingStillValid()

        // Never touch Back — always let it fall straight through to the app/system.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false
        }

        val digitChar = digitCharFor(keyCode)

        // --- Numeric mode: keys type literal digits, nothing is predicted ---
        if (mode == Mode.NUMBER && digitChar != null) {
            currentInputConnection?.commitText(digitChar.toString(), 1)
            lastCommitLength = 0
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
                    lastCommitLength = 0
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
                isSentenceStart = true
                true
            }
            // Deliberately NOT intercepting KEYCODE_DPAD_CENTER, DPAD_UP/DOWN/LEFT/RIGHT:
            // leaving these untouched keeps accessibility/cursor tools working normally.
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
        updateStatusNotification()
        sendTypingState(true)
    }

    private fun togglePredictive() {
        predictiveEnabled = !predictiveEnabled
        currentDigits.clear()
        candidateIndex = 0
        lastMultitapKeyCode = -1
        updateStatusNotification()
        sendTypingState(true)
    }

    /** Used only for predictive (T9) word candidates. Proper mode capitalizes only at the
     *  start of a sentence (isSentenceStart), not after every space. CAPS still gives
     *  explicit uppercase since that's a deliberate user choice, not automatic. Multi-tap
     *  mode has its own separate casing (casedChar below) and is unaffected. */
    private fun applyCase(word: String): String = when (mode) {
        Mode.LOWER -> word.lowercase()
        Mode.CAPS -> word.uppercase()
        Mode.PROPER -> if (isSentenceStart) {
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } else {
            word.lowercase()
        }
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

    /** Short glyph (fits legibly in a tiny status bar icon) for the current mode. */
    private fun iconGlyph(): String = when (mode) {
        Mode.PROPER -> "Aa"
        Mode.CAPS -> "AA"
        Mode.LOWER -> "aa"
        Mode.NUMBER -> "12"
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
        lastCommitLength = 0
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
        val display = bestGuessFor(currentDigits.toString())
        ic.setComposingText(display, 1)
        composingPreviewText = display
    }

    private fun commitCurrentWord(appendSpace: Boolean) {
        val ic = currentInputConnection
        val code = currentDigits.toString()
        if (code.isNotEmpty()) {
            val word = bestGuessFor(code)
            val textToCommit = word + if (appendSpace) " " else ""
            ic?.commitText(textToCommit, 1)
            lastCommitLength = textToCommit.length
            isSentenceStart = false // a real word was just committed; sentence has started
        } else if (appendSpace) {
            ic?.commitText(" ", 1)
            lastCommitLength = 0 // a bare space alone isn't a "predicted word" to undo
        }
        currentDigits.clear()
        candidateIndex = 0
        lastActionWasPunct = false
        composingPreviewText = ""
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
        lastCommitLength = 0
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
        val chosen = punctuations[punctCycleIndex]
        ic.commitText(chosen, 1)
        lastActionWasPunct = true
        lastMultitapKeyCode = -1
        isWordStart = true
        lastCommitLength = 0
        isSentenceStart = chosen == "." || chosen == "!" || chosen == "?"
    }

    /** Returns true only if we actually consumed the key (i.e. there was something to delete). */
    private fun onBackspace(): Boolean {
        if (predictiveEnabled && currentDigits.isNotEmpty()) {
            currentDigits.deleteCharAt(currentDigits.length - 1)
            candidateIndex = 0
            if (currentDigits.isEmpty()) {
                currentInputConnection?.commitText("", 1) // clears the composing span
                composingPreviewText = ""
            } else {
                updateComposing()
            }
            lastActionWasPunct = false
            lastCommitLength = 0
            return true
        }

        lastActionWasPunct = false
        lastMultitapKeyCode = -1
        val ic = currentInputConnection ?: return false

        if (predictiveEnabled && lastCommitLength > 0) {
            // One-shot: the very next backspace right after a predicted word was confirmed
            // deletes the whole word (and its trailing space, if any) instead of one letter.
            ic.deleteSurroundingText(lastCommitLength, 0)
            lastCommitLength = 0
            return true
        }

        val before = ic.getTextBeforeCursor(1, 0)
        return if (!before.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
            true
        } else {
            // Nothing to delete: don't consume the key, so it can propagate normally.
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
        lastCommitLength = 0
        composingPreviewText = ""
        isSentenceStart = true
    }

    // --- Status bar notification (mode indicator) ---

    private fun statusText(): String =
        if (mode == Mode.NUMBER) "123" else "${caseLabel()} • ${if (predictiveEnabled) "T9" else "MULTI"}"

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "TyrionDictionary mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kasalukuyang mode ng TyrionDictionary keyboard"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /** Draws the current mode's short glyph onto a small bitmap to use as the actual status
     *  bar icon — so the mode is visible at a glance without pulling down the shade, and
     *  updates live every time updateStatusNotification() is called (every mode change). */
    private fun buildStatusIcon(): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val glyph = iconGlyph()
        // Shrink the font a bit for two-character glyphs so both letters fit clearly.
        paint.textSize = if (glyph.length > 1) size * 0.52f else size * 0.7f
        val yPos = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(glyph, size / 2f, yPos, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun updateStatusNotification() {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(buildStatusIcon())
            .setContentTitle("TyrionDictionary")
            .setContentText(statusText())
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; skip silently.
        }
    }

    private fun cancelStatusNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
    }

    // --- Typing-state broadcast (lets ClickyCursor's accessibility service know
    //     we're actively composing, since it can no longer see an IME window from us) ---

    private fun sendTypingState(typing: Boolean) {
        val intent = Intent(ACTION_TYPING_STATE).apply {
            setPackage(CLICKY_CURSOR_PACKAGE)
            putExtra(EXTRA_TYPING, typing)
            putExtra(EXTRA_STATUS, if (typing) statusText() else "")
        }
        sendBroadcast(intent)
    }
}
