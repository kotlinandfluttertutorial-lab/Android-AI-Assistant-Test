/// Riverpod provider for [ObservabilityService].
///
/// The service is a singleton for the app lifetime.  It auto-flushes on a
/// timer and on app backgrounding (via the WidgetsBinding lifecycle).
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/observability/data/observability_api.dart';
import 'package:ai_assistant_flutter/features/observability/services/observability_service.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final observabilityServiceProvider = Provider<ObservabilityService>((ref) {
  final dio     = ref.watch(dioProvider);
  final api     = ObservabilityApi(dio);
  final service = ObservabilityService(api: api);

  // Flush on dispose (e.g. hot restart, logout provider scope rebuild).
  ref.onDispose(() {
    service.dispose();
  });

  return service;
});

/// [WidgetsBindingObserver] that flushes events when the app goes to background.
///
/// Register this in the root widget or `main()`:
///   WidgetsBinding.instance.addObserver(
///     AppLifecycleObserver(observabilityService),
///   );
class AppLifecycleObserver extends WidgetsBindingObserver {
  AppLifecycleObserver(this._service);
  final ObservabilityService _service;

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      _service.info(
        'app_lifecycle',
        'App lifecycle: ${state.name}',
      );
      unawaited(_service.flush());
    } else if (state == AppLifecycleState.resumed) {
      _service.info('app_lifecycle', 'App lifecycle: resumed');
    }
  }
}
