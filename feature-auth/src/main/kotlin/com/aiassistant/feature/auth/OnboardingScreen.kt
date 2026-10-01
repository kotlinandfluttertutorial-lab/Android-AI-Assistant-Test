/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : OnboardingScreen.kt
 * Purpose    : Onboarding flow — Phase B UI update.
 *
 *              Page 0 (Welcome) redesigned to match Stitch design reference
 *              (OnboardingScreen.tsx):
 *              - 3-slide horizontal pager within the Welcome page
 *              - Each slide: gradient illustration card with large emoji,
 *                animated title/desc, Skip button top-right
 *              - Progress dots: active dot expands to 24 × 8 pill
 *              - Next circular FAB (56 dp) with arrow icon
 *              - "Get Started" full-width button visible on last slide only
 *
 *              Pages 1 (Privacy) and 2 (Consent) are UNCHANGED — production
 *              legal/consent flow preserved exactly.
 *
 * Architecture Layer : Feature (feature-auth) — Compose UI layer.
 *
 * Preserved from prior version:
 *              - OnboardingScreen signature (onConsentGiven, onDecline)
 *              - HorizontalPager 3-page structure (Welcome / Privacy / Consent)
 *              - ConsentPage and PrivacyPolicyPage composables (unchanged)
 *              - ConsentToggleRow, FeatureHighlight helpers (unchanged)
 *              - All permission-handling logic
 *
 * Requirements: 16.3, 17.1, 28.3
 * ============================================================
 */
package com.aiassistant.feature.auth

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.StitchType
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.launch

// ── Page constants ────────────────────────────────────────────────────────────

private const val PAGE_COUNT   = 3
private const val PAGE_WELCOME = 0
private const val PAGE_PRIVACY = 1
private const val PAGE_CONSENT = 2

// ── Slide data for the welcome pager ─────────────────────────────────────────

private data class OnboardingSlide(
    val emoji: String,
    val title: String,
    val description: String,
    val gradientStart: Color,
    val gradientEnd: Color,
)

private val SLIDES = listOf(
    OnboardingSlide(
        emoji = "🤖",
        title = "Meet Your AI\nAssistant",
        description = "Powered by advanced AI, I can help you write, analyze, code, translate, and much more — all in one place.",
        gradientStart = StitchColors.onboardingSlide0Start,
        gradientEnd   = StitchColors.onboardingSlide0End,
    ),
    OnboardingSlide(
        emoji = "⚡",
        title = "Limitless\nCapabilities",
        description = "Chat, analyze PDFs, process images, write code, transcribe voice, and access 12+ specialized AI tools.",
        gradientStart = StitchColors.onboardingSlide1Start,
        gradientEnd   = StitchColors.onboardingSlide1End,
    ),
    OnboardingSlide(
        emoji = "🔒",
        title = "Private &\nSecure",
        description = "Your conversations are encrypted and never shared. Your data stays yours — always.",
        gradientStart = StitchColors.onboardingSlide2Start,
        gradientEnd   = StitchColors.onboardingSlide2End,
    ),
)

// ── Root screen ───────────────────────────────────────────────────────────────

/**
 * Onboarding flow presented to first-time users.
 *
 * Three outer pages:
 * 1. **Welcome** — 3-slide Stitch-style intro (redesigned).
 * 2. **Privacy & Terms** — scrollable legal text (unchanged).
 * 3. **Consent** — required ToS toggle, analytics, notifications (unchanged).
 *
 * @param onConsentGiven Invoked when the user checks required consent and taps "Continue".
 * @param onDecline      Invoked when the user skips / declines.
 */
@Composable
fun OnboardingScreen(onConsentGiven: () -> Unit, onDecline: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    var requiredConsentChecked       by remember { mutableStateOf(false) }
    var analyticsConsentChecked      by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionGranted = granted
        }
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Pager ─────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            when (page) {
                PAGE_WELCOME -> WelcomePage(onSkip = onDecline)
                PAGE_PRIVACY -> PrivacyPolicyPage()
                PAGE_CONSENT -> ConsentPage(
                    requiredConsentChecked        = requiredConsentChecked,
                    analyticsConsentChecked       = analyticsConsentChecked,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onRequiredConsentChange       = { requiredConsentChecked = it },
                    onAnalyticsConsentChange      = { analyticsConsentChecked = it },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationPermissionGranted = true
                        }
                    },
                )
            }
        }

        // ── Outer page indicator dots (Privacy / Consent progress) ────────────
        // Only shown on pages 1 and 2 (Welcome handles its own dots internally)
        if (pagerState.currentPage > PAGE_WELCOME) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(PAGE_COUNT) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = "Page ${index + 1} of $PAGE_COUNT" },
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ) {}
                    }
                }
            }
        }

        // ── Outer navigation buttons (Privacy / Consent only) ─────────────────
        if (pagerState.currentPage > PAGE_WELCOME) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical   = MaterialTheme.spacing.sm,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Go to previous page" },
                ) { Text("Back") }

                if (pagerState.currentPage < PAGE_COUNT - 1) {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "Go to next page" },
                    ) { Text("Next") }
                } else {
                    Button(
                        onClick = onConsentGiven,
                        enabled = requiredConsentChecked,
                        modifier = Modifier.semantics { contentDescription = "Continue to the app" },
                    ) { Text("Continue") }
                }
            }
        }
    }
}

// ── Welcome page — Stitch 3-slide design ─────────────────────────────────────

/**
 * Self-contained 3-slide welcome experience matching the Stitch design.
 *
 * Contains its own [HorizontalPager], skip button, progress dots, Next FAB,
 * and "Get Started" button.
 *
 * [onSkip] fires when the user taps Skip (routes to Login / next outer page).
 */
@Composable
private fun WelcomePage(onSkip: () -> Unit) {
    val slidePagerState = rememberPagerState(pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReducedMotionEnabled.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Skip button top-right ─────────────────────────────────────────────
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 24.dp)
                .semantics { contentDescription = "Skip onboarding" },
        ) {
            Text(
                text = "Skip",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Slide illustration pager ──────────────────────────────────────
            HorizontalPager(
                state = slidePagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.50f),
            ) { index ->
                val slide = SLIDES[index]
                SlideIllustrationCard(
                    emoji         = slide.emoji,
                    gradientStart = slide.gradientStart,
                    gradientEnd   = slide.gradientEnd,
                )
            }

            // ── Text content ──────────────────────────────────────────────────
            val currentSlide = SLIDES[slidePagerState.currentPage]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 36.dp),
            ) {
                Text(
                    text = currentSlide.title,
                    style = StitchType.onboardingTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = currentSlide.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Progress dots + Next FAB row ──────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 24.dp),
            ) {
                // Animated progress dots — active dot expands to 24 × 8 pill
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(SLIDES.size) { index ->
                        val isActive = slidePagerState.currentPage == index
                        val dotWidth by animateDpAsState(
                            targetValue = if (isActive) 24.dp else 8.dp,
                            animationSpec = if (reducedMotion) tween(0) else tween(300),
                            label = "dotWidth$index",
                        )
                        val dotColor by animateColorAsState(
                            targetValue = if (isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            animationSpec = if (reducedMotion) tween(0) else tween(300),
                            label = "dotColor$index",
                        )
                        Box(
                            modifier = Modifier
                                .size(width = dotWidth, height = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(dotColor)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { scope.launch { slidePagerState.animateScrollToPage(index) } },
                                )
                                .semantics {
                                    contentDescription = "Go to slide ${index + 1}"
                                    role = Role.Button
                                },
                        )
                    }
                }

                // Next circular FAB
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                val next = slidePagerState.currentPage + 1
                                if (next < SLIDES.size) {
                                    scope.launch { slidePagerState.animateScrollToPage(next) }
                                } else {
                                    onSkip() // last slide → proceed
                                }
                            },
                        )
                        .semantics {
                            contentDescription = if (slidePagerState.currentPage < SLIDES.size - 1) "Next slide" else "Get started"
                            role = Role.Button
                        },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // ── "Get Started" full-width button (last slide only) ─────────────
            if (slidePagerState.currentPage == SLIDES.size - 1) {
                Button(
                    onClick = onSkip,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 32.dp)
                        .height(52.dp)
                        .semantics { contentDescription = "Get started" },
                ) {
                    Text(
                        text = "Get Started",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

// ── Slide illustration card ───────────────────────────────────────────────────

/**
 * Gradient rounded-rect card with a large emoji hero, matching the Stitch
 * 340 px illustration area.  Decorative overflow circles add depth.
 */
@Composable
private fun SlideIllustrationCard(
    emoji: String,
    gradientStart: Color,
    gradientEnd: Color,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(listOf(gradientStart, gradientEnd)),
            ),
    ) {
        // Decorative overflow orbs (non-semantic)
        Box(
            modifier = Modifier
                .size(180.dp)
                .align(Alignment.TopEnd)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Box(
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.BottomStart)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.10f)),
        )

        // Large emoji hero
        Text(
            text = emoji,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 96.sp,
                lineHeight = 100.sp,
            ),
            modifier = Modifier.semantics { contentDescription = "" }, // decorative
        )
    }
}

// ── Privacy policy page (UNCHANGED from prior version) ───────────────────────

@Composable
private fun PrivacyPolicyPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Policy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.sm))
            Text(
                text = "Privacy & Terms",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Privacy Policy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Text(
                text = """
AI Assistant is committed to protecting your privacy. We collect only the minimum data required to provide our services.

Data We Collect:
• Account information (email address)
• Conversation history (stored encrypted on your device)
• Usage analytics (optional, requires your consent)

Data Storage:
• All credentials and tokens are stored using Android EncryptedSharedPreferences
• Biometric data is never transmitted — authentication is performed entirely on-device
• Conversations are stored locally and only synced to our servers when you are connected

Your Rights:
• You can delete your account and all associated data at any time
• You can opt out of optional analytics at any time in Settings
• You can request a copy of your data by contacting support
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.lg))
            Text("Terms of Service", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Text(
                text = """
By using AI Assistant, you agree to these terms:

Acceptable Use:
• You must not use the app for illegal activities or to harm others
• You are responsible for the content of your conversations
• AI responses are provided as-is and should not substitute professional advice

Service:
• We reserve the right to modify or discontinue the service with reasonable notice
• We are not liable for any damages arising from the use of AI-generated content

These terms are governed by applicable law. By continuing, you acknowledge that you have read and understood both the Privacy Policy and Terms of Service.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

// ── Consent page (UNCHANGED from prior version) ───────────────────────────────

@Composable
private fun ConsentPage(
    requiredConsentChecked: Boolean,
    analyticsConsentChecked: Boolean,
    notificationPermissionGranted: Boolean,
    onRequiredConsentChange: (Boolean) -> Unit,
    onAnalyticsConsentChange: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Your Consent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(
            text = "Please review and accept the following before continuing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.lg))

        ConsentToggleRow(
            title       = "Privacy Policy & Terms of Service",
            description = "I agree to the Privacy Policy and Terms of Service (required to use the app).",
            checked     = requiredConsentChecked,
            onCheckedChange = onRequiredConsentChange,
            contentDescription = "Toggle agreement to Privacy Policy and Terms of Service",
            isRequired  = true,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        ConsentToggleRow(
            title       = "Analytics Data Collection",
            description = "Allow optional analytics data collection to help improve the app. You can change this in Settings at any time.",
            checked     = analyticsConsentChecked,
            onCheckedChange = onAnalyticsConsentChange,
            contentDescription = "Toggle optional analytics data collection",
            isRequired  = false,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.md))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = if (notificationPermissionGranted) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.sm))
                Text("Notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            Text(
                text = "Allow AI Assistant to send notifications for reminders and updates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            if (notificationPermissionGranted) {
                Text("✓ Notifications enabled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(
                    onClick = onRequestNotificationPermission,
                    modifier = Modifier.semantics { contentDescription = "Allow notification permission" },
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(MaterialTheme.spacing.xs))
                    Text("Allow Notifications")
                }
            } else {
                Text("Notifications will be enabled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Shared helpers (UNCHANGED) ─────────────────────────────────────────────────

@Composable
private fun ConsentToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    isRequired: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (isRequired) {
                    Spacer(Modifier.width(4.dp))
                    Text("*", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(MaterialTheme.spacing.sm))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.semantics { this.contentDescription = contentDescription })
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "OnboardingScreen — Welcome slide 0")
@Composable
private fun OnboardingPreview() {
    AppTheme(dynamicColor = false) {
        OnboardingScreen(onConsentGiven = {}, onDecline = {})
    }
}
