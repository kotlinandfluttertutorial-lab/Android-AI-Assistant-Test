/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : AppIcons.kt
 * Purpose    : Centralized icon registry for the AI Assistant design system.
 *              All icon usages across every feature module must import from
 *              here — never reference Icons.* directly from composables.
 *
 * Architecture Layer : Core-UI — design system foundation.
 *                      Consumed by all feature composables that show icons.
 *                      Never referenced from domain or data layers.
 *
 * Dependencies       : Compose Material Icons (core + extended).
 *
 * Design Decision    : A single registry enforces icon consistency (same icon
 *                      for the same concept everywhere), makes icon audits
 *                      trivial, and prevents accidental use of emoji or text
 *                      characters as UI icons.
 *
 *                      Icons are grouped by functional domain (navigation,
 *                      chat, AI, documents, settings, status) so contributors
 *                      know exactly where to look.
 *
 *                      All icons use Material Symbols / Material Icons from
 *                      the official Compose icon library.  When an exact icon
 *                      does not exist in the standard set, the closest semantic
 *                      match is used and documented with a comment.
 *
 * Requirements       : 13.1–13.4 (icon consistency), 23.4 (no color-only status)
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbDownOffAlt
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbUpOffAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Circle

/**
 * Centralized icon registry.
 *
 * Usage:
 * ```kotlin
 * Icon(imageVector = AppIcons.Send, contentDescription = "Send message")
 * Icon(imageVector = AppIcons.Navigation.Back, contentDescription = "Navigate back")
 * ```
 *
 * All icons are [androidx.compose.ui.graphics.vector.ImageVector] instances from the
 * Material Icons library. No emojis, Unicode characters, or text are used as icons.
 */
object AppIcons {

    // ── Navigation ────────────────────────────────────────────────────────────

    object Navigation {
        val Back          = Icons.AutoMirrored.Filled.ArrowBack
        val Forward       = Icons.AutoMirrored.Filled.ArrowForward
        val Menu          = Icons.Filled.Menu
        val Close         = Icons.Filled.Close
        val MoreVert      = Icons.Filled.MoreVert
        val ExpandMore    = Icons.Filled.ExpandMore
        val ExpandLess    = Icons.Filled.ExpandLess
    }

    // ── Bottom Navigation / Rail / Drawer destinations ────────────────────────

    object Destinations {
        // Filled (selected state)
        val HomeFilled      = Icons.Filled.Home
        val ChatFilled      = Icons.AutoMirrored.Filled.Chat
        val DocumentsFilled = Icons.Filled.Description
        val HistoryFilled   = Icons.Filled.History
        val SettingsFilled  = Icons.Filled.Settings

        // Outlined (unselected state)
        val HomeOutlined      = Icons.Outlined.Home
        val ChatOutlined      = Icons.Outlined.Chat
        val DocumentsOutlined = Icons.Outlined.Description
        val HistoryOutlined   = Icons.Outlined.History
        val SettingsOutlined  = Icons.Outlined.Settings

        // Additional drawer items
        val NewChat   = Icons.Filled.Add
        val Favorites = Icons.Filled.Star
        val FavoritesOutlined = Icons.Outlined.Star
        val Profile   = Icons.Filled.Person
        val ProfileOutlined = Icons.Outlined.Person
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    object Chat {
        val Send        = Icons.AutoMirrored.Filled.Send
        val Stop        = Icons.Filled.Stop
        val Attach      = Icons.Filled.AttachFile
        val Mic         = Icons.Filled.Mic
        val MicOff      = Icons.Filled.MicOff
        val Camera      = Icons.Filled.CameraAlt
        val Compare     = Icons.Filled.Compare
        val Copy        = Icons.Filled.ContentCopy
        val CopyDone    = Icons.Filled.Check          // used after copy confirmation
        val Share       = Icons.Filled.Share
        val Regenerate  = Icons.Filled.Refresh
        val ThumbUp     = Icons.Filled.ThumbUp
        val ThumbUpOutlined  = Icons.Filled.ThumbUpOffAlt
        val ThumbDown   = Icons.Filled.ThumbDown
        val ThumbDownOutlined = Icons.Filled.ThumbDownOffAlt
        val Pin         = Icons.Filled.PushPin
        val Bookmark    = Icons.Filled.Bookmark
        val BookmarkOutlined = Icons.Filled.BookmarkBorder
        val Archive     = Icons.Filled.Archive
        val Rename      = Icons.Filled.Edit
        val Delete      = Icons.Filled.Delete
        val Search      = Icons.Filled.Search
        val Code        = Icons.Filled.Code
    }

    // ── AI modes ──────────────────────────────────────────────────────────────
    // Each AI mode has a distinct icon to communicate the inference source.

    object Ai {
        /**
         * On-device Gemma — uses [OfflineBolt] to communicate local, fast, private.
         * This is the closest semantic match in the standard icon set to "on-device AI".
         */
        val Gemma   = Icons.Filled.OfflineBolt

        /**
         * Cloud AI — uses [Cloud] to communicate remote inference.
         */
        val Cloud   = Icons.Filled.Cloud

        /**
         * Cloud AI offline / unavailable.
         */
        val CloudOff = Icons.Filled.CloudOff

        /**
         * RAG (Retrieval-Augmented Generation) — uses [Source] to communicate
         * document grounding. [Hub] is used as an alternate for connected-documents.
         */
        val Rag     = Icons.Filled.Source

        /**
         * Generic AI / assistant — used in avatars, navigation rail items, and
         * when the specific mode is unknown or mixed.
         */
        val Assistant = Icons.Filled.SmartToy
        val AssistantOutlined = Icons.Outlined.SmartToy

        /**
         * Model management / settings — [Tune] communicates model configuration.
         */
        val ModelSettings = Icons.Filled.Tune

        /**
         * Model download in progress.
         */
        val Download = Icons.Filled.Download

        /**
         * Model ready / loaded — green check treatment applied by caller.
         */
        val Ready    = Icons.Filled.CheckCircle

        /**
         * Model error state.
         */
        val Error    = Icons.Filled.ErrorOutline

        /**
         * Model memory / RAM — [Memory] communicates hardware resource usage.
         */
        val Memory   = Icons.Filled.Memory

        /**
         * On-device indicator dot (used as a small status dot in AiModeIndicator).
         */
        val StatusDot = Icons.Filled.Circle
    }

    // ── Documents / RAG ───────────────────────────────────────────────────────

    object Documents {
        val Document   = Icons.Filled.Description
        val Folder     = Icons.Filled.Folder
        val Upload     = Icons.Filled.Upload
        val Add        = Icons.Filled.Add
        val Delete     = Icons.Filled.Delete
        val Source     = Icons.Filled.Source
        val Citation   = Icons.AutoMirrored.Outlined.Article
        val Indexed    = Icons.Filled.CheckCircle
        val Processing = Icons.Filled.SyncAlt
        val Failed     = Icons.Filled.ErrorOutline
    }

    // ── Status / feedback ─────────────────────────────────────────────────────

    object Status {
        val Offline  = Icons.Filled.WifiOff
        val Online   = Icons.Filled.RadioButtonChecked
        val Syncing  = Icons.Filled.SyncAlt
        val Error    = Icons.Filled.ErrorOutline
        val Warning  = Icons.Filled.WarningAmber
        val Success  = Icons.Filled.CheckCircle
        val Info     = Icons.Filled.Info
        val Lock     = Icons.Filled.Lock
        val Security = Icons.Filled.Security
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    object Settings {
        val Appearance = Icons.Filled.Tune
        val Storage    = Icons.Filled.Storage
        val Privacy    = Icons.Filled.Security
        val About      = Icons.Filled.Info
        val Account    = Icons.Filled.PersonSearch
    }
}
