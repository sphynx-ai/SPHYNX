package com.sphynx.app

// HELP NEXT TO EACH CONTROL (owner, 2026-11-14, like the game's own settings): the descriptions of the buttons
// and switches are not on the page. A small "?" sits at the end of the control a description belongs to;
// tapping it shows the description in a bubble next to it, and a tap anywhere else closes it. (Before: one
// "?" in the header that showed every description of the page while held.)
//
// A description is a line of text the page writes once, when it is built (an explanation of what a
// button does), right after the control it explains. A line the page keeps rewriting (a live status, a
// count, a reading) is not one: it stays on the page. Bold lines (headings, emphasis), field labels (the
// name of an input box) and lines the page marks as information (the app version) are not descriptions.
object HelpHold {

    // is this freshly made line a description (behind a "?")?
    fun isDescription(text: CharSequence?, bold: Boolean, describe: Boolean): Boolean =
        describe && !bold && !text.isNullOrBlank()

    // a description whose text is later rewritten by the page was a live line after all: show it for good
    fun staysDescription(textAtCreation: CharSequence, textNow: CharSequence): Boolean =
        textAtCreation.toString() == textNow.toString()

    /** The bubble's text: the control's description lines, in page order, blank ones left out. */
    fun bubbleText(lines: List<CharSequence>): String =
        lines.map { it.toString().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    /** What a screen reader says for the "?": "Help: " and the start of the description. */
    fun spokenLabel(lines: List<CharSequence>): String {
        val t = bubbleText(lines).replace('\n', ' ')
        val first = t.substringBefore(". ").let { if (it.length < t.length) "$it." else it }
        return "Help: " + if (first.length <= 80) first else first.take(77).trimEnd() + "…"
    }
}
