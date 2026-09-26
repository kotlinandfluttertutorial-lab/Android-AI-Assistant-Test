/// Design token color palette — Material 3 extended with DevOps/AI semantics.
///
/// Both light and dark values are defined. The theme builder in [AppTheme]
/// consumes these tokens, so changing a token here propagates everywhere.
library;

import 'package:flutter/material.dart';

class AppColors {
  AppColors._();

  // ── Brand ─────────────────────────────────────────────────────────────────
  static const Color primaryLight = Color(0xFF6366F1);   // Indigo
  static const Color primaryDark  = Color(0xFF818CF8);

  static const Color secondaryLight = Color(0xFF0EA5E9); // Sky
  static const Color secondaryDark  = Color(0xFF38BDF8);

  // ── AI accent (violet) ────────────────────────────────────────────────────
  static const Color aiAccentLight = Color(0xFF8B5CF6);
  static const Color aiAccentDark  = Color(0xFFA78BFA);

  // ── Severity ──────────────────────────────────────────────────────────────
  static const Color criticalLight = Color(0xFFEF4444);
  static const Color criticalDark  = Color(0xFFF87171);

  static const Color warningLight  = Color(0xFFF59E0B);
  static const Color warningDark   = Color(0xFFFCD34D);

  static const Color healthyLight  = Color(0xFF10B981);
  static const Color healthyDark   = Color(0xFF34D399);

  static const Color infoLight     = Color(0xFF3B82F6);
  static const Color infoDark      = Color(0xFF60A5FA);

  // ── Surface ───────────────────────────────────────────────────────────────
  static const Color surfaceLight     = Color(0xFFF8FAFC);
  static const Color surfaceDark      = Color(0xFF0F172A);

  static const Color cardLight        = Color(0xFFFFFFFF);
  static const Color cardDark         = Color(0xFF1E293B);

  static const Color cardTonalLight   = Color(0xFFF1F5F9);
  static const Color cardTonalDark    = Color(0xFF334155);

  static const Color mutedLight       = Color(0xFF64748B);
  static const Color mutedDark        = Color(0xFF94A3B8);

  // ── Chat bubbles ──────────────────────────────────────────────────────────
  static const Color userBubbleLight  = Color(0xFF6366F1);
  static const Color userBubbleDark   = Color(0xFF4F46E5);

  static const Color aiBubbleLight    = Color(0xFFFFFFFF);
  static const Color aiBubbleDark     = Color(0xFF1E293B);

  // ── Gradient ─────────────────────────────────────────────────────────────
  static const Color gradientALight   = Color(0xFF6366F1);
  static const Color gradientADark    = Color(0xFF4F46E5);

  static const Color gradientBLight   = Color(0xFF0EA5E9);
  static const Color gradientBDark    = Color(0xFF0284C7);
}
