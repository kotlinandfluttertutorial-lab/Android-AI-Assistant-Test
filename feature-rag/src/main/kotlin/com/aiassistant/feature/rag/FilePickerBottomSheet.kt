/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : FilePickerBottomSheet.kt
 * Purpose    : FilePickerBottomSheet — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : FilePickerBottomSheet.kt
 * Purpose    : FilePickerBottomSheet — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * FilePickerBottomSheet.kt
 *
 * Purpose: A bottom sheet composable that allows the user to pick a document from the
 *          device and initiate upload to the RAG pipeline.
 * Architecture: feature-rag â€” Compose UI layer.
 * Dependencies: Compose Material 3, Activity Result APIs (OpenDocument),
 *               core-ui (AppTheme, spacing tokens)
 *
 * Design decisions:
 * - [ActivityResultContracts.OpenDocument] is used for native system file picking so
 *   the app does not need to request broad storage permissions.
 * - File size validation is performed immediately after the user picks a file; if the
 *   file exceeds 50 MB, an inline error message is shown inside the sheet rather than
 *   dismissing and showing a Toast â€” keeping the user in context.
 * - MIME type filter is passed to [OpenDocument] so the system file picker only shows
 *   supported files, but the in-app check remains as a defensive measure.
 * - Upload progress is shown inside the sheet while [RAGUiState.UploadInProgress] is
 *   active.
 *
 * Requirements: 4.1, 27.2, 27.5
 */
package com.aiassistant.feature.rag

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.launch

/** MIME types accepted by the RAG pipeline (requirement 4.1). */
private val SUPPORTED_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
    "text/markdown",
    "text/x-markdown"
)

/** 50 MB limit in bytes. */
private const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L

/**
 * Bottom sheet for selecting and uploading a document to the RAG pipeline.
 *
 * @param uiState           Current [RAGUiState] from [RAGViewModel] to display
 *                          upload progress or success/error feedback.
 * @param onUpload          Callback invoked when the user picks a valid file and taps
 *                          "Upload". Provides uri, fileName, mimeType, sizeBytes.
 * @param onDismiss         Callback to close the sheet.
 * @param sheetState        [SheetState] controlling the sheet animation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePickerBottomSheet(
    uiState: RAGUiState,
    onUpload: (uri: String, fileName: String, mimeType: String, sizeBytes: Long) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Transient state kept within the bottom sheet.
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedMimeType by remember { mutableStateOf<String?>(null) }
    var selectedSizeBytes by remember { mutableStateOf<Long>(0L) }
    var sizeError by remember { mutableStateOf<String?>(null) }

    // System file picker launcher.
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // Resolve file metadata from the content URI.
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        var fileName = uri.lastPathSegment ?: "document"
        var sizeBytes = 0L

        cursor?.use { c ->
            val nameColumn = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeColumn = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameColumn >= 0) fileName = c.getString(nameColumn) ?: fileName
                if (sizeColumn >= 0) sizeBytes = c.getLong(sizeColumn)
            }
        }

        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        // Validate file size inline.
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            sizeError = "\"$fileName\" is too large (${sizeBytes.formatFileSize()}). Maximum is 50 MB."
            selectedUri = null
            selectedFileName = null
        } else {
            sizeError = null
            selectedUri = uri
            selectedFileName = fileName
            selectedMimeType = mimeType
            selectedSizeBytes = sizeBytes
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.md
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
        ) {
            Text(
                text = "Upload Document",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Supported formats: PDF, DOCX, TXT, Markdown Â· Max 50 MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            when (uiState) {
                is RAGUiState.UploadInProgress -> {
                    // Show progress indicator while upload is in-flight.
                    UploadProgressContent(fileName = uiState.fileName)
                }

                is RAGUiState.UploadSuccess -> {
                    // Brief success state before the sheet auto-dismisses.
                    UploadSuccessContent(document = uiState.document)
                }

                else -> {
                    // Default state: file picker + upload action.
                    FileSelectionContent(
                        selectedFileName = selectedFileName,
                        selectedSizeBytes = selectedSizeBytes,
                        sizeError = sizeError,
                        uploadError = (uiState as? RAGUiState.UploadError)?.message,
                        onPickFile = {
                            fileLauncher.launch(SUPPORTED_MIME_TYPES)
                        },
                        onUpload = {
                            val uri = selectedUri ?: return@FileSelectionContent
                            val fileName = selectedFileName ?: return@FileSelectionContent
                            val mimeType = selectedMimeType ?: "application/octet-stream"
                            onUpload(uri.toString(), fileName, mimeType, selectedSizeBytes)
                        },
                        onCancel = {
                            coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                                onDismiss()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
        }
    }
}

// â”€â”€â”€ Sub-composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Default content: file browser button, selected file info, inline error, and action buttons.
 */
@Composable
private fun FileSelectionContent(
    selectedFileName: String?,
    selectedSizeBytes: Long,
    sizeError: String?,
    uploadError: String?,
    onPickFile: () -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit
) {
    // File picker button.
    OutlinedButton(
        onClick = onPickFile,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Select a file to upload" }
    ) {
        Icon(
            imageVector = Icons.Filled.AttachFile,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
        Text(text = if (selectedFileName != null) "Change file" else "Select file")
    }

    // Show selected file name + size.
    if (selectedFileName != null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = selectedFileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            Text(
                text = selectedSizeBytes.formatFileSize(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Inline file-size or upload error.
    val errorToShow = sizeError ?: uploadError
    if (errorToShow != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text(
                text = errorToShow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics {
                    contentDescription = "Error: $errorToShow"
                }
            )
        }
    }

    Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

    // Action row.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Cancel file upload" }
        ) {
            Text("Cancel")
        }
        Button(
            onClick = onUpload,
            enabled = selectedFileName != null && sizeError == null,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Upload selected file" }
        ) {
            Icon(
                imageVector = Icons.Filled.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text("Upload")
        }
    }
}

/** Upload in-progress indicator shown while waiting for the backend to accept the file. */
@Composable
private fun UploadProgressContent(fileName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Uploading $fileName" }
        )
        Text(
            text = "Uploading \"$fileName\"â€¦",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Please wait while the file is being uploaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Brief success feedback shown after a successful upload. */
@Composable
private fun UploadSuccessContent(document: com.aiassistant.domain.model.Document) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "Upload started!",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "\"${document.fileName}\" is being processed. The status will update automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "FilePickerBottomSheet â€“ Default")
@Composable
private fun FilePickerBottomSheetDefaultPreview() {
    AppTheme(dynamicColor = false) {
        FileSelectionContent(
            selectedFileName = null,
            selectedSizeBytes = 0L,
            sizeError = null,
            uploadError = null,
            onPickFile = {},
            onUpload = {},
            onCancel = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "FilePickerBottomSheet â€“ File Selected")
@Composable
private fun FilePickerBottomSheetFileSelectedPreview() {
    AppTheme(dynamicColor = false) {
        FileSelectionContent(
            selectedFileName = "quarterly_report.pdf",
            selectedSizeBytes = 2_456_789L,
            sizeError = null,
            uploadError = null,
            onPickFile = {},
            onUpload = {},
            onCancel = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "FilePickerBottomSheet â€“ Size Error")
@Composable
private fun FilePickerBottomSheetSizeErrorPreview() {
    AppTheme(dynamicColor = false) {
        FileSelectionContent(
            selectedFileName = null,
            selectedSizeBytes = 0L,
            sizeError = "\"large_file.pdf\" is too large (62.5 MB). Maximum is 50 MB.",
            uploadError = null,
            onPickFile = {},
            onUpload = {},
            onCancel = {}
        )
    }
}

@Preview(showBackground = true, name = "FilePickerBottomSheet â€“ Uploading")
@Composable
private fun FilePickerBottomSheetUploadingPreview() {
    AppTheme(dynamicColor = false) {
        UploadProgressContent(fileName = "quarterly_report.pdf")
    }
}
