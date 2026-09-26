/// Environment-driven API configuration.
///
/// Select the environment at build time:
///   flutter run --dart-define=ENV=local
///   flutter run --dart-define=ENV=stage
///   flutter run --dart-define=ENV=production
///
/// Defaults to [AppEnvironment.local] when ENV is not provided, so `flutter run`
/// works out of the box with a locally running backend.
library;

enum AppEnvironment {
  local,
  stage,
  production;

  /// Construct from the --dart-define=ENV=<value> string.
  static AppEnvironment fromString(String value) {
    return switch (value.toLowerCase()) {
      'stage' || 'staging' => AppEnvironment.stage,
      'production' || 'prod' => AppEnvironment.production,
      _ => AppEnvironment.local,
    };
  }
}

/// Read the ENV dart-define (defaults to 'local').
const String _rawEnv = String.fromEnvironment('ENV', defaultValue: 'local');

/// The active environment for this build.
final AppEnvironment currentEnvironment = AppEnvironment.fromString(_rawEnv);

/// Typed API configuration resolved from the active environment.
class ApiConfig {
  ApiConfig._();

  static AppEnvironment get environment => currentEnvironment;

  /// Base HTTP URL — no trailing slash.
  static String get baseUrl => switch (currentEnvironment) {
        // Android emulator reaches the host machine via 10.0.2.2
        AppEnvironment.local => 'http://10.0.2.2:8000',
        AppEnvironment.stage => 'https://api-stage.ai-assistant.example.com',
        AppEnvironment.production => 'https://api.ai-assistant.example.com',
      };

  /// Base WebSocket URL — no trailing slash.
  static String get wsBaseUrl => switch (currentEnvironment) {
        AppEnvironment.local => 'ws://10.0.2.2:8000',
        AppEnvironment.stage => 'wss://api-stage.ai-assistant.example.com',
        AppEnvironment.production => 'wss://api.ai-assistant.example.com',
      };

  // ── Endpoint paths ──────────────────────────────────────────────────────
  static const String authLogin = '/auth/login';
  static const String authRegister = '/auth/register';
  static const String authRefresh = '/auth/refresh';
  static const String authLogout = '/auth/logout';
  static const String authGoogle = '/auth/google';

  static const String chatMessage = '/chat/message';
  static const String chatV1 = '/api/v1/chat';

  static const String conversations = '/conversations';
  static String conversationMessages(String id) => '/conversations/$id/messages';
  static String conversationExport(String id) => '/conversations/$id/export';

  static const String incidents = '/incidents';
  static String incidentById(String id) => '/incidents/$id';
  static String incidentRca(String id) => '/incidents/$id/rca';
  static String incidentRemediationRecommend(String id) =>
      '/incidents/$id/remediation/recommend';
  static String incidentRemediationApprove(String id, String actionId) =>
      '/incidents/$id/remediation/$actionId/approve';
  static String incidentRemediationReject(String id, String actionId) =>
      '/incidents/$id/remediation/$actionId/reject';

  static const String devopsChat = '/devops/chat';
  static const String devopsTools = '/devops/tools';

  static const String observabilityEvents = '/api/v1/observability/events';

  static const String analysisErrors = '/analysis/errors';

  /// WebSocket chat path — append ?token=<jwt> before connecting.
  static String wsChatPath(String conversationId) =>
      '/ws/chat/$conversationId';

  static const String health = '/health';
  static const String ready = '/ready';
}
