package com.lekha.keyboard.engine

/**
 * The offline correction engine. Pure Kotlin, no models, fully deterministic:
 * the same input always yields the same output, and every fix carries a reason.
 * This is Tiers 0 and 1 of the design; the neural GEC and LLM tiers slot in later
 * behind this same shape.
 */
class RuleEngine {

    /** Returns a Correction if the sentence can be improved, or null if it is already fine. */
    fun correct(sentence: String): Correction? {
        val trimmed = sentence.trim()
        if (trimmed.length < 2) return null

        val sink = mutableListOf<Explanation>()
        var s = trimmed
        s = CharRules.apply(s, sink)
        s = GrammarRules.apply(s, sink)
        s = capitalizeFirst(s, sink)

        return if (s != trimmed) Correction(trimmed, s, dedupe(sink)) else null
    }

    private fun capitalizeFirst(input: String, sink: MutableList<Explanation>): String {
        val i = input.indexOfFirst { it.isLetter() }
        if (i < 0) return input
        val c = input[i]
        if (c.isUpperCase()) return input
        sink += Explanation(
            EditTag.CAPS,
            "Start a sentence with a capital letter.",
            "ವಾಕ್ಯವನ್ನು ದೊಡ್ಡ ಅಕ್ಷರದಿಂದ ಪ್ರಾರಂಭಿಸಿ."
        )
        return input.substring(0, i) + c.uppercaseChar() + input.substring(i + 1)
    }

    /** One explanation per category keeps the "Why?" panel short and readable. */
    private fun dedupe(list: List<Explanation>): List<Explanation> {
        val seen = HashSet<EditTag>()
        return list.filter { seen.add(it.tag) }
    }
}
