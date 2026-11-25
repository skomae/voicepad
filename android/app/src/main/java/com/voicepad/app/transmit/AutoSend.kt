package com.voicepad.app.transmit

/** What VoicePad types on the connected computer without being asked. */
enum class AutoSend(val label: String, val short: String) {
    /** Only explicit sends (Send buttons, double-tap, "Type on paired device"). */
    OFF("No auto send", "Off"),

    /** Copying (incl. auto-copy when a recording finishes) also types the original text. */
    ON_COPY("Auto send on copy", "Copy"),

    /** Copying and finished recordings type prompt 1's rewrite; double-tap too. */
    AFTER_LLM("Auto send after LLM prompt", "LLM"),
}
