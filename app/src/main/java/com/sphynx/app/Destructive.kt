package com.sphynx.app

// Buttons that delete or reset something: drawn in the danger colour and always confirmed first
// (Phase 3 design tokens, "destructive"; backlog item 2).
object Destructive {
    val VERBS = listOf("Delete", "Clear", "Forget", "Reset", "Remove")

    // true when the button label starts with one of the verbs as a whole word
    fun isLabel(label: CharSequence): Boolean {
        val first = label.trim().split(' ', limit = 2).firstOrNull() ?: return false
        return VERBS.any { it.equals(first, ignoreCase = true) }
    }
}
