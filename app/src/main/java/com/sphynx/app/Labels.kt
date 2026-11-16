package com.sphynx.app

// LABELS NEVER BREAK INSIDE A WORD (audit finding 23, backlog item 10): Android breaks a word that is wider than
// its line ("Equipmen/t"). A short label (grid caption, tab) is drawn at the largest size, between max and min,
// at which its longest word fits on one line; if even the smallest size is too wide it is cut with "…" instead.
// Pure: unit-tested; MainActivity measures the words.
object Labels {

    /** The words a line can break between. */
    fun words(text: CharSequence): List<String> = text.split(' ', '\n', '\t', '-', '/').filter { it.isNotEmpty() }

    /**
     * The text size (sp) to draw at: the largest of [maxSp], [maxSp] - [step], … down to [minSp] at which a word
     * [widthAt1Sp] pixels wide per sp fits in [availablePx]. Null when it does not fit even at [minSp].
     */
    fun fitSp(widthAt1Sp: Float, availablePx: Float, maxSp: Float, minSp: Float, step: Float = 0.5f): Float? {
        if (widthAt1Sp <= 0f) return maxSp
        if (availablePx <= 0f) return null
        var sp = maxSp
        while (sp >= minSp - 1e-4f) {
            if (widthAt1Sp * sp <= availablePx) return sp
            sp -= step
        }
        return null
    }
}
