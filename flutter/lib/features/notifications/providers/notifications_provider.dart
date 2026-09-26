/// Riverpod providers for push notifications / FCM token.
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/notifications/data/notifications_api.dart';
import 'package:ai_assistant_flutter/features/notifications/services/fcm_token_service.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final notificationsApiProvider = Provider<NotificationsApi>((ref) {
  return NotificationsApi(ref.watch(dioProvider));
});

final fcmTokenServiceProvider = Provider<FcmTokenService>((ref) {
  return FcmTokenService(ref.watch(notificationsApiProvider));
});
