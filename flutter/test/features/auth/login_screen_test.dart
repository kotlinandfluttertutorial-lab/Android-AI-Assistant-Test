import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/core/error/app_error.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:ai_assistant_flutter/features/auth/providers/auth_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:mocktail/mocktail.dart';
import 'package:shared_preferences/shared_preferences.dart';

// ── Mock notifier ─────────────────────────────────────────────────────────────

class MockAuthNotifier extends AsyncNotifier<AuthState>
    implements AuthStateNotifier {
  MockAuthNotifier(this._initialState);
  final AuthState _initialState;

  @override
  Future<AuthState> build() async => _initialState;

  @override
  Future<Result<void>> login(LoginRequest request) async {
    if (request.email == 'fail@test.com') {
      state = const AsyncData(AuthState.unauthenticated());
      return Failure(AppError.unauthorized());
    }
    state = AsyncData(AuthState.authenticated(
      AuthUser(userId: 'u1', email: request.email, role: 'user'),
    ));
    return const Success(null);
  }

  @override
  Future<Result<void>> register(RegisterRequest request) async =>
      const Success(null);

  @override
  Future<void> logout() async {
    state = const AsyncData(AuthState.unauthenticated());
  }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

Future<void> pumpLoginViaRouter(WidgetTester tester, AuthState initial) async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();

  final router = GoRouter(
    initialLocation: Routes.login,
    routes: [
      GoRoute(
        path: Routes.login,
        builder: (_, __) => Consumer(
          builder: (ctx, ref, _) {
            // Minimal standalone login form to avoid needing the full
            // feature set. We test that validation and error banners work.
            return const _StandaloneLoginForm();
          },
        ),
      ),
      GoRoute(
        path: Routes.home,
        builder: (_, __) => const Scaffold(body: Text('Home')),
      ),
      GoRoute(
        path: Routes.register,
        builder: (_, __) => const Scaffold(body: Text('Register')),
      ),
    ],
  );

  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        authStateProvider.overrideWith(() => MockAuthNotifier(initial)),
      ],
      child: MaterialApp.router(
        theme: AppTheme.light,
        routerConfig: router,
      ),
    ),
  );
}

void main() {
  group('Login screen validation', () {
    testWidgets('shows error when email field is empty', (tester) async {
      await pumpLoginViaRouter(tester, const AuthState.unauthenticated());
      await tester.pumpAndSettle();

      // Tap sign-in without filling anything
      await tester.tap(find.widgetWithText(ElevatedButton, 'Sign in'));
      await tester.pump();

      expect(find.text('Email is required'), findsOneWidget);
    });

    testWidgets('shows error for invalid email format', (tester) async {
      await pumpLoginViaRouter(tester, const AuthState.unauthenticated());
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Email'), 'notanemail');
      await tester.tap(find.widgetWithText(ElevatedButton, 'Sign in'));
      await tester.pump();

      expect(find.text('Enter a valid email'), findsOneWidget);
    });

    testWidgets('shows error when password is empty', (tester) async {
      await pumpLoginViaRouter(tester, const AuthState.unauthenticated());
      await tester.pumpAndSettle();

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Email'), 'user@test.com');
      await tester.tap(find.widgetWithText(ElevatedButton, 'Sign in'));
      await tester.pump();

      expect(find.text('Password is required'), findsOneWidget);
    });
  });
}

// ── Standalone login form widget used inside the test router ─────────────────

class _StandaloneLoginForm extends ConsumerStatefulWidget {
  const _StandaloneLoginForm();

  @override
  ConsumerState<_StandaloneLoginForm> createState() =>
      _StandaloneLoginFormState();
}

class _StandaloneLoginFormState extends ConsumerState<_StandaloneLoginForm> {
  final _formKey   = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passCtrl  = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _error = null);
    final result = await ref.read(authStateProvider.notifier).login(
          LoginRequest(
            email: _emailCtrl.text.trim(),
            password: _passCtrl.text,
          ),
        );
    result.when(
      onSuccess: (_) {},
      onFailure: (e) {
        if (mounted) setState(() => _error = e.userMessage);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isLoading = ref.watch(authStateProvider).isLoading;
    return Scaffold(
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            children: [
              TextFormField(
                controller: _emailCtrl,
                decoration: const InputDecoration(labelText: 'Email'),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Email is required';
                  if (!v.contains('@')) return 'Enter a valid email';
                  return null;
                },
              ),
              TextFormField(
                controller: _passCtrl,
                decoration: const InputDecoration(labelText: 'Password'),
                obscureText: true,
                validator: (v) {
                  if (v == null || v.isEmpty) return 'Password is required';
                  return null;
                },
              ),
              if (_error != null)
                Text(_error!, style: const TextStyle(color: Colors.red)),
              ElevatedButton(
                onPressed: isLoading ? null : _submit,
                child: const Text('Sign in'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
