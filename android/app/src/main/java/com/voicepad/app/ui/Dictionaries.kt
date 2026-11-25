package com.voicepad.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepad.app.data.DictionaryStore
import com.voicepad.app.data.DictionaryWords
import com.voicepad.app.data.UserDictionary

/**
 * Asked after an edit is saved: which of the words the edit introduced should
 * go into a dictionary. Every word is its own checkbox (all ticked to start),
 * with Check all / Uncheck all, and a choice of dictionary when there's more
 * than one.
 */
@Composable
fun SaveWordsDialog(
    words: List<String>,
    store: DictionaryStore,
    onDismiss: () -> Unit,
) {
    val dictionaries by store.dictionaries.collectAsStateWithLifecycle()
    val checked = remember(words) { words.map { true }.toMutableStateList() }
    var targetId by remember { mutableStateOf(store.lastUsedId ?: dictionaries.first().id) }
    val target = dictionaries.firstOrNull { it.id == targetId } ?: dictionaries.first()
    val count = checked.count { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add new words to a dictionary?") },
        text = {
            Column {
                Text(
                    "Dictionary words are spelled your way in future transcripts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { checked.indices.forEach { checked[it] = true } }, enabled = count < words.size) {
                        Text("Check all")
                    }
                    TextButton(onClick = { checked.indices.forEach { checked[it] = false } }, enabled = count > 0) {
                        Text("Uncheck all")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(words.size) { index ->
                        CheckRow(words[index], checked[index]) { checked[index] = it }
                    }
                }
                HorizontalDivider()
                if (dictionaries.size > 1) {
                    Text("Save to", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        dictionaries.forEach { d ->
                            FilterChip(
                                selected = d.id == target.id,
                                onClick = { targetId = d.id },
                                label = { Text(d.name + if (d.enabled) "" else " (off)") },
                            )
                        }
                    }
                } else {
                    Text(
                        "Saved to “${target.name}”",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = count > 0,
                onClick = {
                    store.addWords(target.id, words.filterIndexed { i, _ -> checked[i] })
                    onDismiss()
                },
            ) { Text(if (count == 1) "Add 1 word" else "Add $count words") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Settings: each dictionary with an on/off switch; tap one to edit its words. */
@Composable
fun DictionariesSection(store: DictionaryStore) {
    val dictionaries by store.dictionaries.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    Text("Dictionaries", style = MaterialTheme.typography.titleSmall)
    Text(
        "Names, jargon and other words the models get wrong. Words in enabled dictionaries are spelled " +
            "your way in every transcript (and so in translations). When you edit a note, VoicePad offers " +
            "to save the new words.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    dictionaries.forEach { d ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editingId = d.id }
                .padding(vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name)
                Text(
                    wordCount(d.words.size) + (d.words.takeIf { it.isNotEmpty() }?.let { " · " + it.take(4).joinToString(", ") } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { editingId = d.id }) { Icon(Icons.Default.Edit, contentDescription = "Edit ${d.name}") }
            Switch(checked = d.enabled, onCheckedChange = { store.setEnabled(d.id, it) })
        }
    }
    TextButton(onClick = { creating = true }) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("New dictionary")
    }

    if (creating) {
        NameDialog(
            title = "New dictionary",
            initial = "",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name -> editingId = store.create(name).id; creating = false },
        )
    }
    editingId?.let { id ->
        dictionaries.firstOrNull { it.id == id }?.let { d ->
            DictionaryEditor(d, dictionaries, store, onDismiss = { editingId = null })
        }
    }
}

private fun wordCount(n: Int) = if (n == 1) "1 word" else "$n words"

/**
 * One dictionary full screen: add, edit and delete words, rename it, turn it
 * on or off, and move selected words to another dictionary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryEditor(
    dictionary: UserDictionary,
    all: List<UserDictionary>,
    store: DictionaryStore,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    // Words removed elsewhere (moved, deleted) drop out of the selection.
    val selection = selected intersect dictionary.words.toSet()
    var newWord by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingWord by remember { mutableStateOf<String?>(null) }
    val others = all.filter { it.id != dictionary.id }

    fun addTyped() {
        // Commas or new lines separate several words; spaces stay inside a phrase.
        val words = newWord.split(',', '\n').mapNotNull(DictionaryWords::clean)
        if (words.isNotEmpty()) store.addWords(dictionary.id, words)
        newWord = ""
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(dictionary.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { TextButton(onClick = onDismiss) { Text("Done") } },
                        actions = {
                            Switch(checked = dictionary.enabled, onCheckedChange = { store.setEnabled(dictionary.id, it) })
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menuOpen = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (others.isEmpty()) "Clear dictionary" else "Delete dictionary") },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).padding(horizontal = 16.dp).imePadding()) {
                    Text(
                        if (dictionary.enabled) "On: these words are used when transcribing."
                        else "Off: these words are kept but not used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        OutlinedTextField(
                            value = newWord,
                            onValueChange = { newWord = it },
                            label = { Text("Add a word or phrase") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addTyped() }),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { addTyped() }, enabled = DictionaryWords.clean(newWord) != null) {
                            Icon(Icons.Default.Add, "Add")
                        }
                    }
                    if (dictionary.words.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { selected = dictionary.words.toSet() },
                                enabled = selection.size < dictionary.words.size,
                            ) { Text("Check all") }
                            TextButton(onClick = { selected = emptySet() }, enabled = selection.isNotEmpty()) { Text("Uncheck all") }
                        }
                        if (selection.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${selection.size} selected",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                            Column {
                                TextButton(onClick = { moveOpen = true }, enabled = others.isNotEmpty()) { Text("Move to…") }
                                DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                                    Text(
                                        "Move ${wordCount(selection.size)} to",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                    others.forEach { other ->
                                        DropdownMenuItem(
                                            text = { Text(other.name) },
                                            onClick = {
                                                store.moveWords(selection, from = dictionary.id, to = other.id)
                                                selected = emptySet()
                                                moveOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = {
                                store.removeWords(dictionary.id, selection)
                                selected = emptySet()
                            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                    }
                    if (dictionary.words.isEmpty()) {
                        Text(
                            "No words yet. Add them here, or save them when you edit a note.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(dictionary.words, key = { it }) { word ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f)) {
                                    CheckRow(word, word in selection) { on ->
                                        selected = if (on) selection + word else selection - word
                                    }
                                }
                                IconButton(onClick = { editingWord = word }) {
                                    Icon(Icons.Default.Edit, "Edit $word", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog("Rename dictionary", dictionary.name, "Rename", onDismiss = { renaming = false }) {
            store.rename(dictionary.id, it); renaming = false
        }
    }
    editingWord?.let { word ->
        NameDialog("Edit word", word, "Save", onDismiss = { editingWord = null }) {
            store.replaceWord(dictionary.id, word, it); editingWord = null
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (others.isEmpty()) "Clear “${dictionary.name}”?" else "Delete “${dictionary.name}”?") },
            text = {
                Text(
                    if (others.isEmpty()) "Its ${wordCount(dictionary.words.size)} will be removed."
                    else "The dictionary and its ${wordCount(dictionary.words.size)} will be removed. " +
                        "To keep some, move them to another dictionary first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (others.isEmpty()) store.removeWords(dictionary.id, dictionary.words)
                    else { store.delete(dictionary.id); onDismiss() }
                }) { Text(if (others.isEmpty()) "Clear" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text) }),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
