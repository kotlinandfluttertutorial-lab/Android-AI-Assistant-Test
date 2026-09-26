import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('LoginResponse.fromJson', () {
    test('parses all fields correctly', () {
      final json = {
        'user_id': 'abc-123',
        'email': 'user@example.com',
        'role': 'user',
        'access_token': 'at.token',
        'refresh_token': 'rt.token',
        'access_token_expires_at': 1_700_000_000_000,
        'refresh_token_expires_at': 1_702_000_000_000,
        'token_type': 'bearer',
      };
      final response = LoginResponse.fromJson(json);
      expect(response.userId, 'abc-123');
      expect(response.email, 'user@example.com');
      expect(response.role, 'user');
      expect(response.accessToken, 'at.token');
      expect(response.refreshToken, 'rt.token');
      expect(response.accessTokenExpiresAt, 1_700_000_000_000);
    });
  });

  group('RegisterResponse.fromJson', () {
    test('parses all fields', () {
      final json = {
        'user_id': 'xyz-456',
        'email': 'new@example.com',
        'access_token': 'at2',
        'refresh_token': 'rt2',
        'access_token_expires_at': 1_000_000,
        'refresh_token_expires_at': 2_000_000,
        'token_type': 'bearer',
      };
      final r = RegisterResponse.fromJson(json);
      expect(r.userId, 'xyz-456');
      expect(r.email, 'new@example.com');
    });
  });

  group('AuthUser', () {
    test('isAdmin is true only for admin role', () {
      const admin = AuthUser(userId: '1', email: 'a@b.com', role: 'admin');
      const user  = AuthUser(userId: '2', email: 'b@b.com', role: 'user');
      expect(admin.isAdmin, isTrue);
      expect(user.isAdmin, isFalse);
    });

    test('isPremium is true for premium and admin', () {
      const premium = AuthUser(userId: '3', email: 'c@b.com', role: 'premium');
      const admin   = AuthUser(userId: '4', email: 'd@b.com', role: 'admin');
      const regular = AuthUser(userId: '5', email: 'e@b.com', role: 'user');
      expect(premium.isPremium, isTrue);
      expect(admin.isPremium, isTrue);
      expect(regular.isPremium, isFalse);
    });

    test('equality is based on userId, email, role', () {
      const a = AuthUser(userId: '1', email: 'a@b.com', role: 'user');
      const b = AuthUser(userId: '1', email: 'a@b.com', role: 'user');
      const c = AuthUser(userId: '2', email: 'a@b.com', role: 'user');
      expect(a, equals(b));
      expect(a, isNot(c));
    });
  });

  group('AuthState', () {
    test('initial state is not authenticated', () {
      const state = AuthState.initial();
      expect(state.isAuthenticated, isFalse);
      expect(state.user, isNull);
    });

    test('authenticated state has user', () {
      const user  = AuthUser(userId: '1', email: 'x@y.com', role: 'user');
      const state = AuthState.authenticated(user);
      expect(state.isAuthenticated, isTrue);
      expect(state.user, user);
    });
  });

  group('LoginRequest.toJson', () {
    test('serialises email and password', () {
      const req = LoginRequest(email: 'me@test.com', password: 'secret123456');
      final json = req.toJson();
      expect(json['email'], 'me@test.com');
      expect(json['password'], 'secret123456');
    });
  });

  group('RegisterRequest.toJson', () {
    test('includes display_name when provided', () {
      const req = RegisterRequest(
        email: 'new@test.com',
        password: 'mypassword12',
        displayName: 'Alice',
      );
      final json = req.toJson();
      expect(json['display_name'], 'Alice');
    });

    test('display_name defaults to empty string', () {
      const req = RegisterRequest(
        email: 'new@test.com',
        password: 'mypassword12',
      );
      expect(req.toJson()['display_name'], '');
    });
  });
}
