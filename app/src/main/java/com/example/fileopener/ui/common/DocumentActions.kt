package com.example.fileopener.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.fileopener.MainActivity
import com.example.fileopener.R
import com.example.fileopener.domain.model.DocumentFormat
import com.example.fileopener.domain.model.DocumentItem
import com.example.fileopener.domain.usecase.RenameDocumentUseCase
import java.io.File

/** Открывает системное меню «Поделиться» для файла; `false`, если отправить некуда. */
fun Context.shareDocument(document: DocumentItem): Boolean {
    val source = document.uri.toUri()
    // Копии вложений лежат во внутреннем хранилище: чужому приложению их отдаём через FileProvider.
    val uri: Uri = if (source.scheme == ContentResolver.SCHEME_FILE) {
        FileProvider.getUriForFile(this, "$packageName.files", File(source.path ?: return false))
    } else {
        source
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType(document.format.mimeTypes.first())
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newUri(contentResolver, document.name, uri)
    return try {
        val chooser = Intent.createChooser(send, getString(R.string.share_chooser_title))
            // Себе отправлять бессмысленно: приложение принимает SEND с этими типами.
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Диалог переименования: имя без расширения выделено, расширение при пустом вводе сохраняется. */
@Composable
fun RenameDocumentDialog(document: DocumentItem, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val baseNameEnd = document.name.lastIndexOf('.').takeIf { it > 0 } ?: document.name.length
    var value by rememberSaveable(document.uri, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(document.name, TextRange(0, baseNameEnd)))
    }
    val valid = RenameDocumentUseCase.normalizeName(document, value.text) != null
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                isError = !valid,
                supportingText = if (valid) null else ({ Text(stringResource(R.string.rename_invalid)) }),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = valid) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun DeleteDocumentDialog(document: DocumentItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = { Text(stringResource(R.string.delete_message, document.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Сохранение документа в диалогах при повороте экрана. */
val DocumentItemSaver: Saver<DocumentItem?, Any> = listSaver(
    save = { document ->
        if (document == null) emptyList() else listOf(
            document.uri, document.name, document.format.name, document.sizeBytes, document.lastModified,
        )
    },
    restore = { values ->
        if (values.isEmpty()) {
            null
        } else {
            DocumentItem(
                uri = values[0] as String,
                name = values[1] as String,
                format = DocumentFormat.valueOf(values[2] as String),
                sizeBytes = values[3] as Long,
                lastModified = values[4] as Long,
            )
        }
    },
)
