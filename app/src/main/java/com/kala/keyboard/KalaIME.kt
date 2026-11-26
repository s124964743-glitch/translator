package com.kala.keyboard

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

class KalaIME : InputMethodService() {

    private lateinit var settings: KalaSettings
    private lateinit var sound: SoundEngine
    private var keyboardView: KeyboardView? = null
    private var controller: InputController? = null

    private val target = object : TextTarget {
        override fun commit(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun setComposing(text: String) {
            currentInputConnection?.setComposingText(text, 1)
        }

        override fun backspace() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        override fun enter() {
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
        }

        override fun wantsCaps(): Boolean {
            val ei = currentInputEditorInfo ?: return false
            if ((ei.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
            val ic = currentInputConnection ?: return false
            return ic.getCursorCapsMode(ei.inputType) != 0
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = KalaSettings(this)
        sound = SoundEngine(this)
        sound.load(settings.switchId)
        ZhuyinDictHolder.ensure(this)
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, settings, sound, null)
        val c = InputController(target, v, settings)
        c.onOpenSettings = {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        c.onSwitchIme = {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        v.listener = c
        keyboardView = v
        controller = c
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sound.load(settings.switchId)
        ZhuyinDictHolder.ensure(this)
        val v = keyboardView ?: return
        val c = controller ?: return
        v.applySettings()
        c.reset()
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) {
            v.showSymbols()
        } else {
            v.showLetters()
        }
        c.refreshCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.finishComposingText()
        controller?.reset()
        super.onFinishInputView(finishingInput)
    }
}
