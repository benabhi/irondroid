package com.irondroid.ai.narrator

/** How the narrator treats what characters say (IRON-101). The common rules do not change with it. */
enum class NarratorStyle(val file: String) {
    /** Indirect speech only, for synthetic voices that cannot portray characters (RF-TTS-07). */
    SOBER("sober"),

    /** Short lines of direct speech with a voice of their own; the default without voice. */
    PERFORMED("performed"),
}

/** Grammatical gender the narrator uses for the player's character; null while the player has not said. */
enum class CharacterGender { FEMININE, MASCULINE }

/**
 * The narrator's system prompt (RF-ENV-02, RF-ENV-10, RF-ENV-12), versioned with [VERSION] and
 * documented in Lific (RNF-MAN-03). It is the common rules of `narrator/rules.md`, the block of the
 * narrator's language and the block of the [NarratorStyle], with the character's name filled in.
 * It is model-facing text, so it is written in English whatever the narration language.
 */
object NarratorPrompt {
    /** Raised whenever any of the prompt files changes, so a narration can be traced to its prompt. */
    const val VERSION = 2

    /** Narration languages with instructions (RF-ENV-10). */
    val LANGUAGES = setOf("es", "en")

    fun build(
        characterName: String,
        language: String = "es",
        style: NarratorStyle = NarratorStyle.PERFORMED,
        gender: CharacterGender? = null,
    ): String {
        require(language in LANGUAGES) { "No narrator instructions for language $language" }
        require(characterName.isNotBlank()) { "The character needs a name" }
        val genderLine = when (gender) {
            CharacterGender.FEMININE -> "$characterName is a woman: use feminine forms for her."
            CharacterGender.MASCULINE -> "$characterName is a man: use masculine forms for him."
            null -> "The player has not said $characterName's gender: avoid gendered words about $characterName until the story makes it clear."
        }
        return listOf(
            read("rules.md").replace("{character}", characterName),
            "## The player's character\n\n$genderLine",
            read("language/$language.md"),
            read("style/${style.file}.md"),
        ).joinToString("\n\n") { it.trim() } + "\n"
    }

    private fun read(name: String): String {
        val stream = checkNotNull(NarratorPrompt::class.java.classLoader.getResourceAsStream("narrator/$name")) { "narrator/$name not found" }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
