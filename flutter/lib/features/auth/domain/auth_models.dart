/// Domain models for authentication.
///
/// These are separate from the raw API DTOs so that the UI layer is
/// decoupled from the wire format.
library;

import 'package:equatable/equatable.dart';

/// Authenticated user identity carried throughout the app session.
class AuthUser extends Equatable {
  const AuthUser({
    required this.userId,
    required this.email,
    required this.role,
  });

  final String userId;
  final String email;
  final String role; // user | premium | admin

  bool get isAdmin   => role == 'admin';
  bool get isPremium => role == 'premium' || role == 'admin';

  @override
  List<Object?> get props => [userId, email, role];
}

/// Top-level auth state used by [AuthStateNotifier].
class AuthState extends Equatable {
  const AuthState({
    required this.isAuthenticated,
    this.user,
  });

  const AuthState.initial() : this(isAuthenticated: false);
  const AuthState.authenticated(AuthUser user)
      : this(isAuthenticated: true, user: user);
  const AuthState.unauthenticated()
      : this(isAuthenticated: false);

  final bool isAuthenticated;
  final AuthUser? user;

  @override
  List<Object?> get props => [isAuthenticated, user];
}

// ── Request models ───────────────────────────────────────────────────────────

class LoginRequest {
  const LoginRequest({required this.email, required this.password});
  final String email;
  final String password;

  Map<String, dynamic> toJson() => {
        'email': email,
        'password': password,
      };
}

class RegisterRequest {
  const RegisterRequest({
    required this.email,
    required this.password,
    this.displayName = '',
  });
  final String email;
  final String password;
  final String displayName;

  Map<String, dynamic> toJson() => {
        'email': email,
        'password': password,
        'display_name': displayName,
      };
}

// ── Response DTOs (match backend contract exactly) ──────────────────────────

class LoginResponse {
  const LoginResponse({
    required this.userId,
    required this.email,
    required this.role,
    required this.accessToken,
    required this.refreshToken,
    required this.accessTokenExpiresAt,
    required this.refreshTokenExpiresAt,
  });

  factory LoginResponse.fromJson(Map<String, dynamic> json) => LoginResponse(
        userId:                 json['user_id'] as String,
        email:                  json['email'] as String,
        role:                   json['role'] as String,
        accessToken:            json['access_token'] as String,
        refreshToken:           json['refresh_token'] as String,
        accessTokenExpiresAt:   json['access_token_expires_at'] as int,
        refreshTokenExpiresAt:  json['refresh_token_expires_at'] as int,
      );

  final String userId;
  final String email;
  final String role;
  final String accessToken;
  final String refreshToken;
  final int accessTokenExpiresAt;
  final int refreshTokenExpiresAt;
}

class RegisterResponse {
  const RegisterResponse({
    required this.userId,
    required this.email,
    required this.accessToken,
    required this.refreshToken,
    required this.accessTokenExpiresAt,
    required this.refreshTokenExpiresAt,
  });

  factory RegisterResponse.fromJson(Map<String, dynamic> json) =>
      RegisterResponse(
        userId:                json['user_id'] as String,
        email:                 json['email'] as String,
        accessToken:           json['access_token'] as String,
        refreshToken:          json['refresh_token'] as String,
        accessTokenExpiresAt:  json['access_token_expires_at'] as int,
        refreshTokenExpiresAt: json['refresh_token_expires_at'] as int,
      );

  final String userId;
  final String email;
  final String accessToken;
  final String refreshToken;
  final int accessTokenExpiresAt;
  final int refreshTokenExpiresAt;
}
