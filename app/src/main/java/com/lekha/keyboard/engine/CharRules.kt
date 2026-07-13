package com.Fluent.keyboard.engine

/** Tier 0: character-level fixes. Fast, universal, no grammar knowledge needed. */
internal object CharRules {

    private val IC = RegexOption.IGNORE_CASE

    // "i" written as a standalone word -> "I"
    private val standaloneI = Rule(
        Regex("""\bi\b"""),
        EditTag.CAPS,
        "We always write 'I' as a capital letter.",
        "'I' ಅನ್ನು ಯಾವಾಗಲೂ ದೊಡ್ಡ ಅಕ್ಷರದಲ್ಲಿ ಬರೆಯಿರಿ."
    ) { "I" }

    // Common contractions typed without an apostrophe. Ambiguous ones (its, ill, id, were,
    // well) are deliberately excluded: precision beats coverage.
    private val contractions = mapOf(
        "dont" to "don't", "cant" to "can't", "wont" to "won't",
        "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't",
        "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "im" to "I'm", "ive" to "I've",
        "youre" to "you're", "youll" to "you'll", "youve" to "you've",
        "theyre" to "they're", "theyll" to "they'll", "theyve" to "they've",
        "weve" to "we've", "thats" to "that's", "whats" to "what's",
        "hes" to "he's", "shes" to "she's", "lets" to "let's"
    )

    private val contractionRule = Rule(
        Regex("""\b(${contractions.keys.joinToString("|")})\b""", IC),
        EditTag.CONTRACTION,
        "Add an apostrophe in short forms, like don't and I'm.",
        "ಸಂಕ್ಷಿಪ್ತ ರೂಪಗಳಲ್ಲಿ apostrophe (') ಸೇರಿಸಿ, ಉದಾ. don't, I'm."
    ) { m -> contractions[m.value.lowercase()] ?: m.value }

    private val spaceBeforePunct = Rule(
        Regex(""" +([,.?!])"""),
        EditTag.SPACING,
        "No space before a comma or full stop.",
        "comma ಅಥವಾ full stop ಮೊದಲು space ಬೇಡ."
    ) { m -> m.groupValues[1] }

    private val doubleSpace = Rule(
        Regex(""" {2,}"""),
        EditTag.SPACING,
        "Use just one space between words.",
        "ಪದಗಳ ನಡುವೆ ಒಂದೇ space ಬಳಸಿ."
    ) { " " }

    fun apply(input: String, sink: MutableList<Explanation>): String {
        var s = input
        s = standaloneI.applyTo(s, sink)
        s = contractionRule.applyTo(s, sink)
        s = spaceBeforePunct.applyTo(s, sink)
        s = doubleSpace.applyTo(s, sink)
        return s
    }
}
