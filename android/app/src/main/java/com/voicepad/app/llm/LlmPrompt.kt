package com.voicepad.app.llm

/**
 * Style presets and prompt construction for the text transformer. The native
 * engine applies the model's own chat template over the same system/user
 * content; [build] is the plain-text form used for logging and by the fake
 * engine.
 */
object LlmPrompt {
    enum class TypeStyle(val label: String, val system: String) {
        CLEANUP(
            "Clean up",
            "You are a careful editor. Correct speech-recognition mistakes, punctuation and capitalisation. " +
                "Keep the meaning and language. Do not add or remove information. Reply with only the corrected text.",
        ),
        FORMAL_EMAIL(
            "Formal email",
            "You are a careful editor. Correct speech-recognition mistakes, then rewrite the text as a concise formal email. " +
                "Reply with only the rewritten text.",
        ),
        FRIENDLY_MESSAGE(
            "Friendly message",
            "You are a careful editor. Correct speech-recognition mistakes, then rewrite the text as a warm, friendly message. " +
                "Reply with only the rewritten text.",
        ),
        BULLET_POINTS(
            "Bullet points",
            "You are a careful editor. Correct speech-recognition mistakes, then rewrite the text as short bullet points. " +
                "Reply with only the bullets.",
        ),
    }

    fun build(style: TypeStyle, text: String): String =
        "${style.system}\n\nText:\n$text"
}
