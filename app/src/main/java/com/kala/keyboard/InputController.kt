package com.kala.keyboard

/** Abstraction over where text goes (the real editor in the IME, or the test field in the app). */
interface TextTarget {
    fun commit(text: String)        // replaces the composing region (if any) with text
    fun setComposing(text: String)  // "" clears the composing region
    fun backspace()
    fun enter()
    fun wantsCaps(): Boolean
}

/** Glue between the keyboard view, the 注音 composer and the text target. */
class InputController(
    private val target: TextTarget,
    private val view: KeyboardView,
    private val settings: KalaSettings
) : KeyboardListener {

    private val composer = ZhuyinComposer()
    private var cands: List<Cand> = emptyList()
    private var assocMode = false
    var onOpenSettings: (() -> Unit)? = null
    var onSwitchIme: (() -> Unit)? = null

    fun isChinese(): Boolean = settings.lang == "zh"

    fun reset() {
        composer.clear()
        cands = emptyList()
        assocMode = false
        view.clearCandidates()
        view.setChinese(isChinese())
    }

    fun refreshCaps() {
        if (!isChinese()) view.setAutoShift(target.wantsCaps())
    }

    private fun refreshComposing() {
        target.setComposing(composer.display())
        cands = composer.candidates(ZhuyinDictHolder.get())
        assocMode = false
        if (cands.isEmpty()) view.clearCandidates() else view.setCandidates(cands.map { it.text })
    }

    private fun showAssoc(committed: String) {
        cands = emptyList()
        assocMode = false
        if (!settings.assocOn || !isChinese() || committed.isEmpty()) {
            view.clearCandidates()
            return
        }
        val dict = ZhuyinDictHolder.get()
        val list = dict?.assoc(committed[committed.length - 1], 30) ?: emptyList()
        if (list.isEmpty()) {
            view.clearCandidates()
        } else {
            cands = list.map { Cand(it, 0) }
            assocMode = true
            view.setCandidates(list)
        }
    }

    private fun commitFirstCandidate() {
        val c = cands.firstOrNull()
        if (c == null) {
            target.commit(composer.display())
            composer.clear()
            view.clearCandidates()
            return
        }
        pick(0)
    }

    private fun pick(index: Int) {
        val c = cands.getOrNull(index) ?: return
        if (composer.isEmpty()) {
            target.commit(c.text)
            showAssoc(c.text)
            return
        }
        val n = composer.size()
        target.commit(c.text)
        if (c.used >= n) {
            composer.clear()
            showAssoc(c.text)
        } else {
            composer.consume(c.used)
            refreshComposing()
        }
    }

    // ------------------------------------------------------------ KeyboardListener

    override fun onText(text: String) {
        if (!composer.isEmpty()) commitFirstCandidate()
        target.commit(text)
        if (isChinese()) view.clearCandidates()
        cands = emptyList()
        assocMode = false
        refreshCaps()
    }

    override fun onZhuyin(c: Char) {
        composer.typeSymbol(c)
        if (composer.isEmpty()) {
            target.setComposing("")
            view.clearCandidates()
        } else {
            refreshComposing()
        }
    }

    override fun onSpace() {
        if (!composer.isEmpty()) {
            if (composer.markFirstTone()) refreshComposing() else commitFirstCandidate()
            return
        }
        if (assocMode) {
            assocMode = false
            cands = emptyList()
            view.clearCandidates()
        }
        target.commit(" ")
        refreshCaps()
    }

    override fun onBackspace() {
        if (!composer.isEmpty()) {
            composer.backspace()
            if (composer.isEmpty()) {
                target.setComposing("")
                cands = emptyList()
                view.clearCandidates()
            } else {
                refreshComposing()
            }
            return
        }
        if (assocMode) {
            assocMode = false
            cands = emptyList()
            view.clearCandidates()
        }
        target.backspace()
        refreshCaps()
    }

    override fun onEnter() {
        if (!composer.isEmpty()) {
            target.commit(composer.display())
            composer.clear()
            cands = emptyList()
            view.clearCandidates()
            return
        }
        assocMode = false
        cands = emptyList()
        view.clearCandidates()
        target.enter()
        refreshCaps()
    }

    override fun onCandidate(index: Int) = pick(index)

    override fun onToggleLanguage() {
        if (!composer.isEmpty()) {
            target.commit(composer.display())
            composer.clear()
        }
        settings.lang = if (isChinese()) "en" else "zh"
        cands = emptyList()
        assocMode = false
        view.clearCandidates()
        view.setChinese(isChinese())
        refreshCaps()
    }

    override fun onOpenSettings() {
        onOpenSettings?.invoke()
    }

    override fun onSwitchIme() {
        onSwitchIme?.invoke()
    }
}
