/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/FeaturedBannerCard.kt
 * Purpose    : Full-width promotional / hero banner card with a two-stop
 *              gradient background, decorative overflow orbs, headline, body
 *              copy, and an optional CTA button.
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by HomeDashboard (home featured card) and
 *                      ToolsScreen (featured tool banner).
 *
 * Design reference   : HomeScreen.tsx — featured gradient card ("Gemini 2.0 Ultra")
 *                      ToolsScreen.tsx — featured card ("Resume Builder AI")
 *
 * Design decisions   :
 * - Gradient is rendered on a [Canvas] via [Brush.linearGradient] — no drawable
 *   resource required.
 * - Decorative orbs are pure [Box] composables with [CircleShape] clip and a
 *   semi-transparent fill; they are marked `contentDescription = null` because
 *   they carry no semantic meaning.
 * - The CTA [OutlinedButton] uses a semi-transparent surface (`#33FFFFFF` white)
 *   so it remains legible against any gradient background colour.
 * - Tag label ("New", "Featured") above the headline is optional — hidden when
 *   [tag] is null.
 * - [contentDescription] on the card root gives TalkBack a concise summary that
 *   includes both headline and body copy.
 *
 * Requirements       : 23.1, 23.4, 24.1
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.StitchType
import com.aiassistant.core.ui.spacing

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Full-width gradient banner card.
 *
 * @param headline      Primary title text (e.g. "Gemini 2.0 Ultra").
 * @param body          Supporting description (1–2 lines).
 * @param gradientStart Left / top colour of the background gradient.
 * @param gradientEnd   Right / bottom colour of the background gradient.
 * @param tag           Optional short tag label above the headline (e.g. "New", "Featured").
 *                      Pass `null` to hide.
 * @param ctaLabel      Label for the call-to-action button (e.g. "Try now →").
 *                      Pass `null` to hide the button entirely.
 * @param onCtaClick    Click handler for the CTA button, and also for the whole card
 *                      when [ctaLabel] is null.
 * @param modifier      Modifier applied to the outer card container.
 */
@Composable
fun FeaturedBannerCard(
    headline: String,
    body: String,
    gradientStart: Color = StitchColors.featuredBannerStart,
    gradientEnd: Color = StitchColors.featuredBannerEnd,
    tag: String? = null,
    ctaLabel: String? = "Try now →",
    onCtaClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val a11yDesc = buildString {
        tag?.let { append("$it. ") }
        append("$headline. $body")
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge) // 28 dp — matches Stitch r20/r32
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(gradientStart, gradientEnd),
                ),
            )
            .clickable(onClick = onCtaClick)
            .semantics { contentDescription = a11yDesc },
    ) {
        // ── Decorative overflow orbs (non-semantic) ───────────────────────────
        // Top-right orb (larger)
        Box(
            modifier = Modifier
                .size(100.dp)
                .offset(x = 20.dp, y = (-20).dp)
                .align(androidx.compose.ui.Alignment.TopEnd)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        // Bottom-right orb (smaller)
        Box(
            modifier = Modifier
                .size(80.dp)
                .offset(x = (-20).dp, y = 20.dp)
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
        )

        // ── Content ───────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.md,
                ),
        ) {
            // Optional tag
            if (tag != null) {
                Text(
                    text = tag.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.70f),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(
                        1f,
                        androidx.compose.ui.unit.TextUnitType.Sp
                    ),
                )
                Spacer(Modifier.height(4.dp))
            }

            // Headline
            Text(
                text = headline,
                style = StitchType.bannerHeadline,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(MaterialTheme.spacing.xs))

            // Body
            Text(
                text = body,
                style = StitchType.bannerBody,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // CTA button
            if (ctaLabel != null) {
                Spacer(Modifier.height(MaterialTheme.spacing.sm + MaterialTheme.spacing.xs))
                OutlinedButton(
                    onClick = onCtaClick,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.18f),
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.28f),
                    ),
                    shape = AppShapes.pill,
                ) {
                    Text(
                        text = ctaLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "FeaturedBannerCard — Home (purple)")
@Composable
private fun HomeBannerPreview() {
    AppTheme(dynamicColor = false) {
        FeaturedBannerCard(
            headline = "Gemini 2.0 Ultra",
            body = "Now with improved reasoning and multimodal capabilities",
            gradientStart = StitchColors.featuredBannerStart,
            gradientEnd = StitchColors.featuredBannerEnd,
            tag = "New",
            ctaLabel = "Try now →",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "FeaturedBannerCard — Tools (rose)")
@Composable
private fun ToolsBannerPreview() {
    AppTheme(dynamicColor = false) {
        FeaturedBannerCard(
            headline = "Resume Builder AI",
            body = "Create ATS-optimized resumes tailored to any job description in minutes.",
            gradientStart = StitchColors.toolsFeaturedStart,
            gradientEnd = StitchColors.toolsFeaturedEnd,
            tag = "Featured",
            ctaLabel = "Try now →",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "FeaturedBannerCard — no CTA")
@Composable
private fun NoCTABannerPreview() {
    AppTheme(dynamicColor = false) {
        FeaturedBannerCard(
            headline = "Master your workflow",
            body = "Try our new multi-modal AI that can analyze documents and generate images.",
            ctaLabel = null,
            modifier = Modifier.padding(16.dp),
        )
    }
}
