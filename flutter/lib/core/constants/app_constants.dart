/// Central place for all application-wide constants.
///
/// Do NOT put URLs here — those live in [ApiConfig] and are driven by the
/// [AppEnvironment] selected at build time.
library;

class AppConstants {
  AppConstants._();

  // ── App identity ────────────────────────────────────────────────────────
  static const String appName = 'AI Assistant';
  static const String appVersion = '1.0.0';

  // ── Networking timeouts ─────────────────────────────────────────────────
  static const Duration connectTimeout = Duration(seconds: 15);
  static const Duration receiveTimeout = Duration(seconds: 30);
  static const Duration sendTimeout = Duration(seconds: 30);

  // ── WebSocket ────────────────────────────────────────────────────────────
  static const Duration wsPingInterval = Duration(seconds: 30);
  static const Duration wsReconnectDelay = Duration(seconds: 3);
  static const int wsMaxReconnectAttempts = 5;

  // ── Auth ─────────────────────────────────────────────────────────────────
  /// How many seconds before expiry to proactively refresh the access token.
  static const int tokenRefreshBufferSeconds = 60;

  // ── Pagination ───────────────────────────────────────────────────────────
  static const int defaultPageSize = 20;

  // ── Chat ─────────────────────────────────────────────────────────────────
  static const int maxMessageLength = 4000;
  static const int conversationHistoryLimit = 50;

  // ── Storage keys ────────────────────────────────────────────────────────
  // Non-sensitive keys stored in SharedPreferences
  static const String prefSelectedProvider = 'selected_ai_provider';
  static const String prefThemeMode = 'theme_mode';
  static const String prefOnboardingComplete = 'onboarding_complete';

  // Sensitive keys stored in flutter_secure_storage
  static const String secureAccessToken = 'access_token';
  static const String secureRefreshToken = 'refresh_token';
  static const String secureAccessTokenExpiry = 'access_token_expiry';
  static const String secureRefreshTokenExpiry = 'refresh_token_expiry';
  static const String secureUserId = 'user_id';
  static const String secureUserEmail = 'user_email';
  static const String secureUserRole = 'user_role';
}
