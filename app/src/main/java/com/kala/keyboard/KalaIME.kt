package com.kala.keyboard

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class KalaIME : InputMethodService(), KeyboardListener {

    private lateinit var settings: KalaSettings
    private lateinit var sound: SoundEngine
    private var keyboardView: KeyboardView? = null

    override fun onCreate() {
        super.onCreate()
        settings = KalaSettings(this)
        sound = SoundEngine(this)
        sound.load(settings.switchId)
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, settings, sound, this)
        keyboardView = v
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sound.load(settings.switchId)
        val v = keyboardView ?: return
        v.applySettings()
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
            v.showSymbols()
        } else {
            v.showLetters()
        }
        v.setAutoShift(shouldAutoCap())
    }

    private fun shouldAutoCap(): Boolean {
        val ei = currentInputEditorInfo ?: return false
        if ((ei.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(ei.inputType) != 0
    }

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        keyboardView?.setAutoShift(shouldAutoCap())
    }

    override fun onBackspace() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
        val ei = currentInputEditorInfo
        val ic = currentInputConnection
        if (ei == null || ic == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            return
        }
        val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            ic.performEditorAction(action)
        }
        keyboardView?.setAutoShift(shouldAutoCap())
    }
}
