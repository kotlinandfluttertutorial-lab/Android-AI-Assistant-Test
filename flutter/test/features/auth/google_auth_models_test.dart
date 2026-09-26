import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // ── GoogleAuthRequest ────────────────────────────────────────────────────────

  group('GoogleAuthRequest.toJson', () {
    test('serialises id_token', () {
      const req = GoogleAuthRequest(idToken: 'eyJhbGci.test.token');
      final json = req.toJson();
      expect(json['id_token'], 'eyJhbGci.test.token');
      expect(json.length, 1); // only one key
    });

    test('preserves empty token string', () {
      const req = GoogleAuthRequest(idToken: '');
      expect(req.toJson()['id_token'], '');
    });
  });

  // ── GoogleAuthResponse ───────────────────────────────────────────────────────

  group('GoogleAuthResponse.fromJson', () {
    final fullJson = {
      'user_id':                  'google-user-uuid',
      'email':                    'alice@gmail.com',
      'display_name':             'Alice Smith',
      'role':                     'user',
      'access_token':             'at.google.token',
      'refresh_token':            'rt.google.token',
      'access_token_expires_at':  1_700_000_000_000,
      'refresh_token_expires_at': 1_702_000_000_000,
      'is_new_user':              true,
    };

    test('parses all fields correctly', () {
      final r = GoogleAuthResponse.fromJson(fullJson);
      expect(r.userId,      'google-user-uuid');
      expect(r.email,       'alice@gmail.com');
      expect(r.displayName, 'Alice Smith');
      expect(r.role,        'user');
      expect(r.accessToken,  'at.google.token');
      expect(r.refreshToken, 'rt.google.token');
      expect(r.accessTokenExpiresAt,  1_700_000_000_000);
      expect(r.refreshTokenExpiresAt, 1_702_000_000_000);
      expect(r.isNewUser,   isTrue);
    });

    test('defaults display_name to empty string when missing', () {
      final json = Map<String, dynamic>.from(fullJson)
        ..remove('display_name');
      final r = GoogleAuthResponse.fromJson(json);
      expect(r.displayName, '');
    });

    test('defaults role to "user" when missing', () {
      final json = Map<String, dynamic>.from(fullJson)
        ..remove('role');
      final r = GoogleAuthResponse.fromJson(json);
      expect(r.role, 'user');
    });

    test('defaults is_new_user to false when missing', () {
      final json = Map<String, dynamic>.from(fullJson)
        ..remove('is_new_user');
      final r = GoogleAuthResponse.fromJson(json);
      expect(r.isNewUser, isFalse);
    });

    test('isNewUser is false for returning user', () {
      final json = Map<String, dynamic>.from(fullJson)
        ..['is_new_user'] = false;
      final r = GoogleAuthResponse.fromJson(json);
      expect(r.isNewUser, isFalse);
    });

    test('can construct an AuthUser from the response', () {
      final r = GoogleAuthResponse.fromJson(fullJson);
      final user = AuthUser(
        userId: r.userId,
        email:  r.email,
        role:   r.role,
      );
      expect(user.userId, 'google-user-uuid');
      expect(user.email,  'alice@gmail.com');
      expect(user.isAdmin,   isFalse);
      expect(user.isPremium, isFalse);
    });
  });
}
