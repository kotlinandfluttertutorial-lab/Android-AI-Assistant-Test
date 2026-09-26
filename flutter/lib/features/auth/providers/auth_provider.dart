/// Riverpod providers for authentication state.
///
/// [authStateProvider] — AsyncValue<AuthState>; watched by GoRouter guard.
/// Call methods on the notifier to trigger login, register, and logout.
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/auth/data/auth_api.dart';
import 'package:ai_assistant_flutter/features/auth/data/auth_repository.dart';
import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

// ── Repository provider ───────────────────────────────────────────────────────

final authRepositoryProvider = Provider<AuthRepository>((ref) {
  return AuthRepository(
    api:     AuthApi(ref.watch(dioProvider)),
    storage: ref.watch(secureStorageProvider),
  );
});

// ── Auth state notifier ───────────────────────────────────────────────────────

class AuthStateNotifier extends AsyncNotifier<AuthState> {
  @override
  Future<AuthState> build() async {
    // Restore session from secure storage on cold start.
    final repo = ref.watch(authRepositoryProvider);
    final user = await repo.restoreSession();
    if (user != null) {
      return AuthState.authenticated(user);
    }
    return const AuthState.unauthenticated();
  }

  Future<Result<void>> login(LoginRequest request) async {
    state = const AsyncLoading();
    final repo   = ref.read(authRepositoryProvider);
    final result = await repo.login(request);
    return result.when(
      onSuccess: (user) {
        state = AsyncData(AuthState.authenticated(user));
        return const Success(null);
      },
      onFailure: (error) {
        state = AsyncData(const AuthState.unauthenticated());
        return Failure(error);
      },
    );
  }

  Future<Result<void>> register(RegisterRequest request) async {
    state = const AsyncLoading();
    final repo   = ref.read(authRepositoryProvider);
    final result = await repo.register(request);
    return result.when(
      onSuccess: (user) {
        state = AsyncData(AuthState.authenticated(user));
        return const Success(null);
      },
      onFailure: (error) {
        state = AsyncData(const AuthState.unauthenticated());
        return Failure(error);
      },
    );
  }

  Future<void> logout() async {
    state = const AsyncLoading();
    final repo = ref.read(authRepositoryProvider);
    await repo.logout();
    state = const AsyncData(AuthState.unauthenticated());
  }
}

final authStateProvider =
    AsyncNotifierProvider<AuthStateNotifier, AuthState>(
  AuthStateNotifier.new,
);
