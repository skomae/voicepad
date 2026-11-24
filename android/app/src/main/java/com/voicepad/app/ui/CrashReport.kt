package com.voicepad.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepad.app.CrashGuard
import com.voicepad.app.CrashLog

/** After an unexpected close: what happened, ready to copy or share into a bug report. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.pending(context)) }
    val text = report ?: return
    fun dismiss() {
        CrashLog.clear(context)
        report = null
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("VoicePad closed unexpectedly") },
        text = {
            SelectionContainer {
                Text(
                    "Copy or share this so it can be fixed:\n\n$text",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, text)
                dismiss()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(share, "Share crash report"))
                dismiss()
            }) { Text("Share") }
        },
    )
}

/**
 * After VoicePad closed while a risky native feature was running (see
 * [CrashGuard]): offer to turn that feature off before it runs again.
 */
@Composable
fun CrashGuardDialog(risky: CrashGuard.Risky, onAnswer: (turnOff: Boolean) -> Unit) {
    val context = LocalContext.current
    val report = remember { CrashLog.pending(context) }
    AlertDialog(
        onDismissRequest = {}, // needs an answer: the feature waits for it
        title = { Text("VoicePad closed while ${risky.doing}") },
        text = {
            Column {
                Text(
                    "That most likely made the app crash. Turn it off? You can turn it back on in " +
                        "conversation settings.",
                )
                if (report != null) {
                    Text(
                        "Crash details were saved — copying them helps get this fixed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { copyToClipboard(context, "During: ${risky.doing}\n\n$report") }) { Text("Copy crash details") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CrashLog.clear(context)
                onAnswer(true)
            }) { Text(risky.offer) }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashLog.clear(context)
                onAnswer(false)
            }) { Text(risky.keep) }
        },
    )
}
