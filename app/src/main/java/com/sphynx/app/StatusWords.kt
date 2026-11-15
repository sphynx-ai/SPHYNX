package com.sphynx.app

// A state is never shown by colour alone (design tokens, accessibility rule 3; Phase 3 backlog item 7):
// every coloured state also has a word, and the dots differ in shape.
object StatusWords {
    const val GRANTED = "Granted"
    const val MISSING = "Missing"

    // a permission button: its label plus the state word ("3. Notifications  ·  Granted")
    fun permission(base: String, granted: Boolean): String = withWord(base, if (granted) GRANTED else MISSING)

    // a button with a done / not done state and its own words
    fun withWord(base: String, word: String): String = "$base  ·  $word"

    // takes a state word added by withWord off again (the button keeps its own label)
    fun base(label: String): String = label.substringBefore("  ·  ")

    // the three-level state of a model / file row: 2 = ready, 1 = partly (loading, or loaded but off), 0 = missing
    fun levelWord(state: Int): String = when (state) { 2 -> "Ready"; 1 -> "Partial"; else -> "Missing" }
    fun levelDot(state: Int): String = when (state) { 2 -> "●"; 1 -> "◐"; else -> "○" }
}
