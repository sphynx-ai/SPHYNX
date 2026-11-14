package com.example.mlbbresearch.app

// HOLD FOR HELP (owner, 2026-11-14): the descriptions under the buttons and switches are hidden; the
// names of the buttons stay. They show only while the small "?" circle in the header is pressed and
// held, and hide again as soon as it is let go.
//
// A description is a line of text the page writes once, when it is built (an explanation of what a
// button does). A line the page keeps rewriting (a live status, a count, a reading) is not one: it
// stays visible. Bold lines (headings, emphasis), field labels (the name of an input box) and lines
// the page marks as information (the app version) are not descriptions either.
object HelpHold {

    // is this freshly made line a description (hidden until "?" is held)?
    fun isDescription(text: CharSequence?, bold: Boolean, describe: Boolean): Boolean =
        describe && !bold && !text.isNullOrBlank()

    // a description whose text is later rewritten by the page was a live line after all: show it for good
    fun staysDescription(textAtCreation: CharSequence, textNow: CharSequence): Boolean =
        textAtCreation.toString() == textNow.toString()
}
