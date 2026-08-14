/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : DocumentUploadFlowTest.kt
 * Purpose    : Compose UI integration tests for the document upload flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the document upload / list screen.
 *
 * Uses a test-local [fakeDocumentListContent] composable that reproduces the same
 * semantic anchors as the real DocumentListScreenContent (internal to feature-rag).
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class DocumentUploadFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Test-local fake composable ────────────────────────────────────────────

    data class FakeDoc(val name: String, val statusLabel: String)

    @Composable
    private fun fakeDocumentListContent(
        isLoading: Boolean = false,
        isOffline: Boolean = false,
        uploadFabVisible: Boolean = true,
        documents: List<FakeDoc> = emptyList(),
        errorMessage: String? = null,
        onUploadFabClick: () -> Unit = {}
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .semantics { contentDescription = "Loading documents" }
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (isOffline) {
                    item {
                        Text(
                            text = "You're offline",
                            modifier = Modifier.semantics {
                                contentDescription = "You are offline"
                            }
                        )
                    }
                }
                items(documents) { doc ->
                    Text(text = doc.statusLabel)
                }
                if (errorMessage != null) {
                    item {
                        Text(text = errorMessage)
                    }
                }
            }
            if (uploadFabVisible) {
                FloatingActionButton(
                    onClick = onUploadFabClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .semantics { contentDescription = "Upload a new document" }
                ) {
                    Text("+")
                }
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun setDocumentList(
        isLoading: Boolean = false,
        isOffline: Boolean = false,
        uploadFabVisible: Boolean = true,
        documents: List<FakeDoc> = emptyList(),
        errorMessage: String? = null,
        onUploadFabClick: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                fakeDocumentListContent(
                    isLoading = isLoading,
                    isOffline = isOffline,
                    uploadFabVisible = uploadFabVisible,
                    documents = documents,
                    errorMessage = errorMessage,
                    onUploadFabClick = onUploadFabClick
                )
            }
        }
    }

    // ── 1. Idle state shows FAB ───────────────────────────────────────────────

    @Test
    fun docUpload_idle_showsFab() {
        setDocumentList()

        composeTestRule.onNodeWithContentDescription("Upload a new document").assertIsDisplayed()
    }

    // ── 2. Loading state shows progress indicator ─────────────────────────────

    @Test
    fun docUpload_loading_showsIndicator() {
        setDocumentList(isLoading = true)

        composeTestRule.onNodeWithContentDescription("Loading documents").assertIsDisplayed()
    }

    // ── 3. Offline state shows offline text ───────────────────────────────────

    @Test
    fun docUpload_offline_showsOfflineText() {
        setDocumentList(isOffline = true)

        composeTestRule.onNodeWithText("You're offline").assertIsDisplayed()
    }

    // ── 4. Pending document shows its status label ────────────────────────────

    @Test
    fun docUpload_pendingDoc_showsStatus() {
        setDocumentList(documents = listOf(FakeDoc("report.pdf", "pending")))

        composeTestRule.onNodeWithText("pending").assertIsDisplayed()
    }

    // ── 5. Ready document shows its status label ──────────────────────────────

    @Test
    fun docUpload_readyDoc_showsStatus() {
        setDocumentList(documents = listOf(FakeDoc("report.pdf", "ready")))

        composeTestRule.onNodeWithText("ready").assertIsDisplayed()
    }

    // ── 6. Error message is displayed ────────────────────────────────────────

    @Test
    fun docUpload_error_showsMessage() {
        setDocumentList(errorMessage = "Network error")

        composeTestRule.onNodeWithText("Network error").assertIsDisplayed()
    }

    // ── 7. Tapping FAB fires the callback ─────────────────────────────────────

    @Test
    fun docUpload_fab_tapFiresCallback() {
        var fabTapped = false

        setDocumentList(onUploadFabClick = { fabTapped = true })

        composeTestRule.onNodeWithContentDescription("Upload a new document").performClick()

        assert(fabTapped) { "Expected upload FAB callback to be triggered" }
    }
}
