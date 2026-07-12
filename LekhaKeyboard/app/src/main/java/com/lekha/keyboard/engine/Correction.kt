package com.lekha.keyboard.engine

/** The category of a single fix. The label is what a learner sees; the tag drives explanations. */
enum class EditTag(val label: String) {
    CAPS("Capital letter"),
    CONTRACTION("Short form"),
    SPACING("Spacing"),
    SVA("Subject and verb"),
    STATIVE("-ing verb"),
    ARTICLE("a / an / the"),
    PREPOSITION("Preposition"),
    TENSE("Tense"),
    SINCE_FOR("since / for"),
    TIME("Time word"),
    QUESTION("Question order"),
    UNCOUNTABLE("Uncountable noun"),
    PLURAL("Plural")
}

/** One plain-English (+ Kannada) reason shown under a suggestion. Never grammar jargon. */
data class Explanation(
    val tag: EditTag,
    val whyEn: String,
    val whyKn: String
)

/** The result of correcting one sentence. corrected == original never happens (we return null). */
data class Correction(
    val original: String,
    val corrected: String,
    val explanations: List<Explanation>
)
