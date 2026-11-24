package com.voicepad.app.ui

/**
 * What a single tap on a note bubble does. Every long-press menu action is
 * available, plus [NOTHING]. An action that doesn't apply to the tapped note
 * (e.g. [TYPE] with no keyboard connected) does nothing.
 */
enum class NoteTapAction(val label: String) {
    EDIT("Edit"),
    COPY("Copy"),
    TYPE("Type on paired device"),
    COPY_TRANSLATION("Copy translation"),
    TRANSLATE("Translate to English"),
    PIN("Pin / unpin"),
    HISTORY("Revision history"),
    PLAY("Play / stop recording"),
    SHARE("Share recording"),
    RETRANSCRIBE("Re-transcribe"),
    ARCHIVE("Archive / unarchive"),
    DELETE("Delete (asks first)"),
    NOTHING("Nothing"),
}
