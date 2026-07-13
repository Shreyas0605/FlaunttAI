package com.Fluent.keyboard.engine

/**
 * A single deterministic correction rule: a pattern, the fix, and why it matters.
 * High precision by design. If it isn't confidently correct, it isn't a rule.
 */
internal class Rule(
    val pattern: Regex,
    val tag: EditTag,
    val whyEn: String,
    val whyKn: String,
    val replace: (MatchResult) -> String
) {
    /** Applies the rule; records an explanation only if the text actually changed. */
    fun applyTo(input: String, sink: MutableList<Explanation>): String {
        if (!pattern.containsMatchIn(input)) return input
        val out = pattern.replace(input) { m -> replace(m) }
        if (out != input) sink += Explanation(tag, whyEn, whyKn)
        return out
    }
}
