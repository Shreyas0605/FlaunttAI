package com.lekha.keyboard.engine

/**
 * Tier 1: curated grammar rules weighted toward Kannada-first speakers.
 * Every rule is high precision. When a rule cannot be confidently correct
 * across contexts, it is scoped down or omitted rather than risk a wrong fix
 * shown to a learner (a wrong correction is worse than a missed one).
 */
internal object GrammarRules {

    private val IC = RegexOption.IGNORE_CASE

    // 1. he / she / it + don't  ->  doesn't
    private val svaDont = Rule(
        Regex("""\b(he|she|it)\s+don't\b""", IC),
        EditTag.SVA,
        "We use 'doesn't' with he, she and it.",
        "he, she, it ಜೊತೆ 'doesn't' ಬಳಸುತ್ತೇವೆ."
    ) { m -> "${m.groupValues[1]} doesn't" }

    // 2. he / she / it + base verb  ->  verb + s   (correct forms only; guarded against
    //    auxiliaries so "does he like" is left alone)
    private val thirdSg = mapOf(
        "have" to "has", "do" to "does", "go" to "goes", "say" to "says",
        "want" to "wants", "need" to "needs", "like" to "likes", "know" to "knows",
        "live" to "lives", "work" to "works", "come" to "comes", "make" to "makes",
        "take" to "takes", "feel" to "feels", "look" to "looks", "play" to "plays",
        "stay" to "stays", "love" to "loves", "call" to "calls", "help" to "helps"
    )
    private val svaVerb = Rule(
        Regex(
            """(?<!(?:do|does|did|to|will|would|can|could|shall|should|may|might|must)\s)""" +
                """\b(he|she|it)\s+(${thirdSg.keys.joinToString("|")})\b""",
            IC
        ),
        EditTag.SVA,
        "Add 's' to the verb after he, she or it.",
        "he, she, it ನಂತರದ ಕ್ರಿಯಾಪದಕ್ಕೆ 's' ಸೇರಿಸಿ."
    ) { m -> "${m.groupValues[1]} ${thirdSg[m.groupValues[2].lowercase()]}" }

    // 3. Stative verbs don't take -ing (scoped to "I am ..." to avoid agreement issues)
    private val statBase = mapOf(
        "knowing" to "know", "understanding" to "understand", "wanting" to "want",
        "needing" to "need", "liking" to "like", "believing" to "believe"
    )
    private val stativeI = Rule(
        Regex("""\bI\s+am\s+(${statBase.keys.joinToString("|")})\b""", IC),
        EditTag.STATIVE,
        "Words like know and want don't use '-ing'.",
        "know, want ರೀತಿಯ ಪದಗಳಿಗೆ '-ing' ಬಳಸುವುದಿಲ್ಲ."
    ) { m -> "I ${statBase[m.groupValues[1].lowercase()]}" }

    // 4. Article before a specific place after "to"
    private val toThePlace = Rule(
        Regex(
            """\bto\s+(market|office|station|bank|hospital|temple|church|mosque|""" +
                """shop|gym|park|airport|library|cinema|theatre|beach)\b""",
            IC
        ),
        EditTag.ARTICLE,
        "Use 'the' before a specific place, like 'the market'.",
        "ನಿರ್ದಿಷ್ಟ ಸ್ಥಳದ ಮೊದಲು 'the' ಬಳಸಿ, ಉದಾ. 'the market'."
    ) { m -> "to the ${m.groupValues[1]}" }

    // 5. Common Indian-English preposition and redundancy fixes
    private val prepRules = listOf(
        Rule(Regex("""\bdiscuss about\b""", IC), EditTag.PREPOSITION,
            "We say 'discuss' without 'about'.",
            "'discuss' ಬಳಸಿ, 'about' ಬೇಡ.") { "discuss" },
        Rule(Regex("""\bmarried with\b""", IC), EditTag.PREPOSITION,
            "We get 'married to' someone, not 'with'.",
            "ಯಾರನ್ನಾದರೂ 'married to' ಎನ್ನುತ್ತೇವೆ.") { "married to" },
        Rule(Regex("""\breturn back\b""", IC), EditTag.PREPOSITION,
            "'Return' already means come back.",
            "'Return' ಎಂದರೆ ಆಗಲೇ ವಾಪಸ್.") { "return" },
        Rule(Regex("""\brevert back\b""", IC), EditTag.PREPOSITION,
            "'Revert' already means to reply.",
            "'Revert' ಎಂದರೆ ಆಗಲೇ ಉತ್ತರಿಸು.") { "revert" },
        Rule(Regex("""\bcope up with\b""", IC), EditTag.PREPOSITION,
            "We say 'cope with', not 'cope up with'.",
            "'cope with' ಎನ್ನುತ್ತೇವೆ.") { "cope with" }
    )

    // 6. "since <duration>" -> "for <duration>"  (won't touch "since 2020": no unit follows)
    private val sinceFor = Rule(
        Regex(
            """\bsince\s+((?:\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\s+""" +
                """(?:year|years|month|months|week|weeks|day|days|hour|hours|minute|minutes))\b""",
            IC
        ),
        EditTag.SINCE_FOR,
        "Use 'for' with a length of time, like 'for two years'.",
        "ಸಮಯದ ಅವಧಿಗೆ 'for' ಬಳಸಿ, ಉದಾ. 'for two years'."
    ) { m -> "for ${m.groupValues[1]}" }

    // 7. Time-word fixes
    private val timeFixes = listOf(
        Rule(Regex("""\byesterday night\b""", IC), EditTag.TIME,
            "We say 'last night'.", "'last night' ಎನ್ನುತ್ತೇವೆ.") { "last night" },
        Rule(Regex("""\btoday morning\b""", IC), EditTag.TIME,
            "We say 'this morning'.", "'this morning' ಎನ್ನುತ್ತೇವೆ.") { "this morning" },
        Rule(Regex("""\btoday night\b""", IC), EditTag.TIME,
            "We say 'tonight'.", "'tonight' ಎನ್ನುತ್ತೇವೆ.") { "tonight" }
    )

    // 8. Question word order (anchored to sentence start to avoid "that is why you are late")
    private val qFixes = listOf(
        Rule(Regex("""^\s*why you are\b""", IC), EditTag.QUESTION,
            "In questions, 'are' comes before 'you'.",
            "ಪ್ರಶ್ನೆಯಲ್ಲಿ 'are' 'you' ಮೊದಲು ಬರುತ್ತದೆ.") { "why are you" },
        Rule(Regex("""^\s*where you are\b""", IC), EditTag.QUESTION,
            "In questions, 'are' comes before 'you'.",
            "ಪ್ರಶ್ನೆಯಲ್ಲಿ 'are' 'you' ಮೊದಲು ಬರುತ್ತದೆ.") { "where are you" },
        Rule(Regex("""^\s*when you are\b""", IC), EditTag.QUESTION,
            "In questions, 'are' comes before 'you'.",
            "ಪ್ರಶ್ನೆಯಲ್ಲಿ 'are' 'you' ಮೊದಲು ಬರುತ್ತದೆ.") { "when are you" },
        Rule(Regex("""^\s*what you are\b""", IC), EditTag.QUESTION,
            "In questions, 'are' comes before 'you'.",
            "ಪ್ರಶ್ನೆಯಲ್ಲಿ 'are' 'you' ಮೊದಲು ಬರುತ್ತದೆ.") { "what are you" }
    )

    // 9. Double past-tense marking after "did"
    private val didPast = mapOf(
        "went" to "go", "came" to "come", "gave" to "give", "took" to "take",
        "made" to "make", "saw" to "see", "ate" to "eat", "got" to "get",
        "bought" to "buy", "brought" to "bring", "taught" to "teach",
        "caught" to "catch", "found" to "find", "told" to "tell", "said" to "say"
    )
    private val didFix = Rule(
        Regex(
            """\bdid\s+(you|he|she|it|they|we|i)\s+(${didPast.keys.joinToString("|")})\b""",
            IC
        ),
        EditTag.TENSE,
        "After 'did', use the simple verb, like 'did you go'.",
        "'did' ನಂತರ ಸರಳ ಕ್ರಿಯಾಪದ ಬಳಸಿ, ಉದಾ. 'did you go'."
    ) { m -> "did ${m.groupValues[1]} ${didPast[m.groupValues[2].lowercase()]}" }

    // 10. Uncountable nouns wrongly pluralised
    private val uncountable = mapOf(
        "informations" to "information", "furnitures" to "furniture",
        "advices" to "advice", "equipments" to "equipment",
        "luggages" to "luggage", "softwares" to "software", "hardwares" to "hardware"
    )
    private val uncountRule = Rule(
        Regex("""\b(${uncountable.keys.joinToString("|")})\b""", IC),
        EditTag.UNCOUNTABLE,
        "This word has no plural. Use it without 's'.",
        "ಈ ಪದಕ್ಕೆ ಬಹುವಚನ ಇಲ್ಲ. 's' ಇಲ್ಲದೆ ಬಳಸಿ."
    ) { m -> uncountable[m.value.lowercase()] ?: m.value }

    // 11. "one of my friend" -> "one of my friends"
    private val oneOf = Rule(
        Regex(
            """\bone of (my|the|his|her|our|your|their)\s+""" +
                """(friend|brother|sister|cousin|colleague|teacher|student|member|""" +
                """book|photo|song|movie|child)\b""",
            IC
        ),
        EditTag.PLURAL,
        "After 'one of my', the noun is plural, like 'friends'.",
        "'one of my' ನಂತರ ನಾಮಪದ ಬಹುವಚನ, ಉದಾ. 'friends'."
    ) { m ->
        val noun = m.groupValues[2].lowercase()
        val plural = when {
            noun == "child" -> "children"
            noun.endsWith("s") || noun.endsWith("x") ||
                noun.endsWith("ch") || noun.endsWith("sh") -> noun + "es"
            else -> noun + "s"
        }
        "one of ${m.groupValues[1]} $plural"
    }

    fun apply(input: String, sink: MutableList<Explanation>): String {
        var s = input
        s = svaDont.applyTo(s, sink)
        s = svaVerb.applyTo(s, sink)
        s = stativeI.applyTo(s, sink)
        s = toThePlace.applyTo(s, sink)
        prepRules.forEach { s = it.applyTo(s, sink) }
        s = sinceFor.applyTo(s, sink)
        timeFixes.forEach { s = it.applyTo(s, sink) }
        qFixes.forEach { s = it.applyTo(s, sink) }
        s = didFix.applyTo(s, sink)
        s = uncountRule.applyTo(s, sink)
        s = oneOf.applyTo(s, sink)
        return s
    }
}
