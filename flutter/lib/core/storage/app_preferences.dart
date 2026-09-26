/// Non-sensitive preferences backed by SharedPreferences.
///
/// Do NOT store tokens, API keys, or PII here.
library;

import 'package:ai_assistant_flutter/core/constants/app_constants.dart';
import 'package:shared_preferences/shared_preferences.dart';

class AppPreferences {
  AppPreferences(this._prefs);

  final SharedPreferences _prefs;

  // ── AI Provider ───────────────────────────────────────────────────────────

  String get selectedProvider =>
      _prefs.getString(AppConstants.prefSelectedProvider) ?? 'gemini';

  Future<void> setSelectedProvider(String provider) =>
      _prefs.setString(AppConstants.prefSelectedProvider, provider);

  // ── Theme ─────────────────────────────────────────────────────────────────

  /// Stored as 'system' | 'light' | 'dark'.
  String get themeMode =>
      _prefs.getString(AppConstants.prefThemeMode) ?? 'system';

  Future<void> setThemeMode(String mode) =>
      _prefs.setString(AppConstants.prefThemeMode, mode);

  // ── Onboarding ────────────────────────────────────────────────────────────

  bool get onboardingComplete =>
      _prefs.getBool(AppConstants.prefOnboardingComplete) ?? false;

  Future<void> setOnboardingComplete() =>
      _prefs.setBool(AppConstants.prefOnboardingComplete, true);
}
