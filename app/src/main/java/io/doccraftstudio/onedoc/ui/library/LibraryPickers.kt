package io.doccraftstudio.onedoc.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.doccraftstudio.onedoc.domain.model.DocumentFormat

/** Системные SAF-диалоги выбора папки и файлов для библиотеки. */
class LibraryPickers(
    val pickFolder: () -> Unit,
    val pickFiles: () -> Unit,
)

private val PICKER_MIME_TYPES: Array<String> =
    DocumentFormat.entries.flatMap { it.mimeTypes }.distinct().toTypedArray()

@Composable
fun rememberLibraryPickers(viewModel: LibraryViewModel): LibraryPickers {
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onFolderPicked(uri?.toString())
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onFilesPicked(uris.map(Uri::toString))
    }
    return remember(folderLauncher, filesLauncher) {
        LibraryPickers(
            pickFolder = { folderLauncher.launch(null) },
            pickFiles = { filesLauncher.launch(PICKER_MIME_TYPES) },
        )
    }
}
