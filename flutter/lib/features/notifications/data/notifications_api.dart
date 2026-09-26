/// API client for device-token registration.
///
///   PUT /notifications/device-token
///   Body: {token: str, platform: "android" | "ios"}
///
/// No response body on success (HTTP 200).
/// Requires valid JWT — must be called after login.
///
/// ## Wiring FCM
///
/// Add `firebase_messaging` to pubspec.yaml:
///   firebase_messaging: ^15.x.x
///
/// Then replace the stub [FcmTokenService.getToken] with:
///   final token = await FirebaseMessaging.instance.getToken();
///
/// Platform setup:
///   Android — place google-services.json in flutter/android/app/
///   iOS      — place GoogleService-Info.plist in flutter/ios/Runner/
///            — add push-notification capability in Xcode
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:dio/dio.dart';

class NotificationsApi {
  const NotificationsApi(this._dio);
  final Dio _dio;

  /// Register (or refresh) the FCM device token for the current user.
  ///
  /// [token]    — FCM registration token from `FirebaseMessaging.getToken()`.
  /// [platform] — 'android' or 'ios'.
  Future<Result<void>> registerDeviceToken({
    required String token,
    required String platform,
  }) async {
    try {
      await _dio.put<void>(
        ApiConfig.notificationsDeviceToken,
        data: {
          'token':    token,
          'platform': platform,
        },
      );
      return const Success(null);
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
