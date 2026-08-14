/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : FocusOrderDefaults.kt
 * Purpose    : FocusOrderDefaults — core-ui module component
 *
 * Architecture Layer : Core-UI
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
 * Module     : core-ui
 * File       : FocusOrderDefaults.kt
 * Purpose    : FocusOrderDefaults — core-ui module component
 *
 * Architecture Layer : Core-UI
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
 * FocusOrderDefaults.kt
 *
 * Purpose: Pre-built focus group size constants and ordering documentation for
 * the primary screens of the AI Assistant application.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: None (pure constants + KDoc).
 * Requirements: 23.5
 *
 * Design decisions:
 * - Focus group sizes are declared as top-level constants so that callers can pass
 *   them directly to [rememberFocusGroup] without embedding magic numbers.
 * - The ordering within each group is documented in a table below and enforced by
 *   the order in which composables call [Modifier.logicalFocusOrder].
 * - All groups are circular: Tab from the last item wraps to index 0, and
 *   Shift+Tab from index 0 wraps to the last item.
 *
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * SCREEN FOCUS ORDERS
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 * ### Login Screen  (feature-auth / LoginScreen.kt)
 * Size: [LOGIN_FOCUS_GROUP_SIZE] = 4
 *
 * | Index | Element              | Notes                          |
 * |-------|----------------------|--------------------------------|
 * |   0   | Email TextField      | First field; receives initial focus on screen open |
 * |   1   | Password TextField   | Tab from email moves here      |
 * |   2   | Login Button         | Primary CTA                    |
 * |   3   | Register Text Button | Secondary action (least-used)  |
 *
 * Usage:
 * ```kotlin
 * val focus = rememberFocusGroup(LOGIN_FOCUS_GROUP_SIZE)
 * OutlinedTextField(modifier = Modifier.logicalFocusOrder(focus, LOGIN_FOCUS_EMAIL))
 * OutlinedTextField(modifier = Modifier.logicalFocusOrder(focus, LOGIN_FOCUS_PASSWORD))
 * Button(modifier = Modifier.logicalFocusOrder(focus, LOGIN_FOCUS_SUBMIT))
 * TextButton(modifier = Modifier.logicalFocusOrder(focus, LOGIN_FOCUS_REGISTER))
 * ```
 *
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 * ### Chat Detail Screen  (feature-chat / ChatDetailScreen.kt)
 * Size: [CHAT_DETAIL_FOCUS_GROUP_SIZE] = 3
 *
 * | Index | Element              | Notes                                         |
 * |-------|----------------------|-----------------------------------------------|
 * |   0   | Message list         | LazyColumn; receives focus for scroll via D-pad |
 * |   1   | Message input field  | TextField for composing a message             |
 * |   2   | Send Button          | Submits the message; Tab from input goes here  |
 *
 * Usage:
 * ```kotlin
 * val focus = rememberFocusGroup(CHAT_DETAIL_FOCUS_GROUP_SIZE)
 * LazyColumn(modifier = Modifier.logicalFocusOrder(focus, CHAT_FOCUS_MESSAGE_LIST))
 * OutlinedTextField(modifier = Modifier.logicalFocusOrder(focus, CHAT_FOCUS_INPUT))
 * IconButton(modifier = Modifier.logicalFocusOrder(focus, CHAT_FOCUS_SEND))
 * ```
 *
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 *
 * ### Settings Screen  (feature-settings / SettingsScreen.kt)
 * Size: [SETTINGS_FOCUS_GROUP_SIZE] = 5
 *
 * | Index | Element                | Notes                                       |
 * |-------|------------------------|---------------------------------------------|
 * |   0   | Theme selector row     | Light / Dark / System toggle                |
 * |   1   | Provider selector row  | LLM provider chooser                        |
 * |   2   | Notification prefs row | Opens notification preference sub-screen    |
 * |   3   | Account/Profile row    | Navigates to profile screen                 |
 * |   4   | Sign out button        | Last item; Tab wraps back to index 0         |
 *
 * Usage:
 * ```kotlin
 * val focus = rememberFocusGroup(SETTINGS_FOCUS_GROUP_SIZE)
 * SettingsRow(modifier = Modifier.logicalFocusOrder(focus, SETTINGS_FOCUS_THEME))
 * SettingsRow(modifier = Modifier.logicalFocusOrder(focus, SETTINGS_FOCUS_PROVIDER))
 * SettingsRow(modifier = Modifier.logicalFocusOrder(focus, SETTINGS_FOCUS_NOTIFICATIONS))
 * SettingsRow(modifier = Modifier.logicalFocusOrder(focus, SETTINGS_FOCUS_ACCOUNT))
 * Button(modifier = Modifier.logicalFocusOrder(focus, SETTINGS_FOCUS_SIGN_OUT))
 * ```
 *
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 */
package com.aiassistant.core.ui.adaptive

// â”€â”€ Login Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Number of focusable elements on the Login screen. */
const val LOGIN_FOCUS_GROUP_SIZE: Int = 4

/** Focus index for the email input field on the Login screen. */
const val LOGIN_FOCUS_EMAIL: Int = 0

/** Focus index for the password input field on the Login screen. */
const val LOGIN_FOCUS_PASSWORD: Int = 1

/** Focus index for the primary login/submit button on the Login screen. */
const val LOGIN_FOCUS_SUBMIT: Int = 2

/** Focus index for the secondary "create account" text button on the Login screen. */
const val LOGIN_FOCUS_REGISTER: Int = 3

// â”€â”€ Chat Detail Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Number of focusable elements on the Chat Detail screen. */
const val CHAT_DETAIL_FOCUS_GROUP_SIZE: Int = 3

/** Focus index for the scrollable message list on the Chat Detail screen. */
const val CHAT_FOCUS_MESSAGE_LIST: Int = 0

/** Focus index for the message compose input field on the Chat Detail screen. */
const val CHAT_FOCUS_INPUT: Int = 1

/** Focus index for the send button on the Chat Detail screen. */
const val CHAT_FOCUS_SEND: Int = 2

// â”€â”€ Settings Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Number of focusable elements on the Settings screen. */
const val SETTINGS_FOCUS_GROUP_SIZE: Int = 5

/** Focus index for the theme selector row on the Settings screen. */
const val SETTINGS_FOCUS_THEME: Int = 0

/** Focus index for the AI provider selector row on the Settings screen. */
const val SETTINGS_FOCUS_PROVIDER: Int = 1

/** Focus index for the notification preferences row on the Settings screen. */
const val SETTINGS_FOCUS_NOTIFICATIONS: Int = 2

/** Focus index for the account/profile row on the Settings screen. */
const val SETTINGS_FOCUS_ACCOUNT: Int = 3

/** Focus index for the sign-out button on the Settings screen. */
const val SETTINGS_FOCUS_SIGN_OUT: Int = 4
