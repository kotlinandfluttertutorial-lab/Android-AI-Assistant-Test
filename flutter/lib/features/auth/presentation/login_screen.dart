/// Login screen — email + password with validation, loading, error states.
library;

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:ai_assistant_flutter/features/auth/providers/auth_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/app_button.dart';
import 'package:ai_assistant_flutter/shared/widgets/app_text_field.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey        = GlobalKey<FormState>();
  final _emailCtrl      = TextEditingController();
  final _passwordCtrl   = TextEditingController();
  bool  _obscurePass    = true;
  String? _errorMessage;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _errorMessage = null);

    final result = await ref.read(authStateProvider.notifier).login(
          LoginRequest(
            email:    _emailCtrl.text.trim(),
            password: _passwordCtrl.text,
          ),
        );

    result.when(
      onSuccess: (_) {}, // GoRouter guard handles navigation
      onFailure: (err) {
        if (mounted) setState(() => _errorMessage = err.userMessage);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isLoading = ref.watch(authStateProvider).isLoading;

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 64),

                // ── Logo / Header ────────────────────────────────────────
                Center(
                  child: Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      gradient: context.heroGradient,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    child: const Icon(
                      Icons.auto_awesome,
                      size: 36,
                      color: Colors.white,
                      semanticLabel: 'AI Assistant logo',
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                Text(
                  'Welcome back',
                  textAlign: TextAlign.center,
                  style: context.texts.headlineMedium,
                ),
                const SizedBox(height: 8),
                Text(
                  'Sign in to your AI Assistant',
                  textAlign: TextAlign.center,
                  style: context.texts.bodyMedium?.copyWith(
                    color: context.mutedColor,
                  ),
                ),

                const SizedBox(height: 40),

                // ── Email ────────────────────────────────────────────────
                AppTextField(
                  controller: _emailCtrl,
                  label: 'Email',
                  hint: 'you@example.com',
                  keyboardType: TextInputType.emailAddress,
                  textInputAction: TextInputAction.next,
                  enabled: !isLoading,
                  validator: (v) {
                    if (v == null || v.trim().isEmpty) {
                      return 'Email is required';
                    }
                    if (!v.contains('@')) return 'Enter a valid email';
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // ── Password ─────────────────────────────────────────────
                AppTextField(
                  controller: _passwordCtrl,
                  label: 'Password',
                  hint: '••••••••••••',
                  obscureText: _obscurePass,
                  textInputAction: TextInputAction.done,
                  enabled: !isLoading,
                  onSubmitted: (_) => _submit(),
                  suffixIcon: IconButton(
                    icon: Icon(
                      _obscurePass
                          ? Icons.visibility_outlined
                          : Icons.visibility_off_outlined,
                    ),
                    tooltip: _obscurePass ? 'Show password' : 'Hide password',
                    onPressed: () =>
                        setState(() => _obscurePass = !_obscurePass),
                  ),
                  validator: (v) {
                    if (v == null || v.isEmpty) return 'Password is required';
                    return null;
                  },
                ),

                // ── Error banner ─────────────────────────────────────────
                if (_errorMessage != null) ...[
                  const SizedBox(height: 16),
                  _ErrorBanner(message: _errorMessage!),
                ],

                const SizedBox(height: 24),

                // ── Sign-in button ────────────────────────────────────────
                AppButton(
                  label: 'Sign in',
                  isLoading: isLoading,
                  onPressed: _submit,
                ),

                const SizedBox(height: 12),

                // ── Divider ───────────────────────────────────────────────
                Row(
                  children: [
                    const Expanded(child: Divider()),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: Text(
                        'or',
                        style: context.texts.bodySmall
                            ?.copyWith(color: context.mutedColor),
                      ),
                    ),
                    const Expanded(child: Divider()),
                  ],
                ),

                const SizedBox(height: 12),

                // ── Google Sign-In button ─────────────────────────────────
                _GoogleSignInButton(
                  isLoading: isLoading,
                  onSignedIn: (error) {
                    if (error != null && mounted) {
                      setState(() => _errorMessage = error);
                    }
                  },
                ),

                const SizedBox(height: 16),

                // ── Register link ─────────────────────────────────────────
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      "Don't have an account? ",
                      style: context.texts.bodyMedium,
                    ),
                    TextButton(
                      onPressed: isLoading
                          ? null
                          : () => context.go(Routes.register),
                      child: const Text('Create account'),
                    ),
                  ],
                ),

                const SizedBox(height: 32),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: context.critical.withAlpha(20),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: context.critical.withAlpha(60)),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: context.critical, size: 20,
              semanticLabel: 'Error'),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: context.texts.bodySmall?.copyWith(
                color: context.critical,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Google Sign-In button.
///
/// This button is a styled wrapper that delegates to [AuthStateNotifier.googleSignIn].
///
/// ## Wiring a real Google Sign-In SDK
///
/// 1. Add the dependency:
///    ```yaml
///    google_sign_in: ^6.2.1
///    ```
///
/// 2. Configure each platform:
///    - Android: place `google-services.json` in `flutter/android/app/`
///    - iOS: place `GoogleService-Info.plist` in `flutter/ios/Runner/`
///
/// 3. Replace the _getGoogleIdToken() stub below with:
///    ```dart
///    final googleSignIn = GoogleSignIn(scopes: ['email']);
///    final account      = await googleSignIn.signIn();
///    final auth         = await account?.authentication;
///    return auth?.idToken;
///    ```
class _GoogleSignInButton extends ConsumerWidget {
  const _GoogleSignInButton({
    required this.isLoading,
    required this.onSignedIn,
  });

  final bool isLoading;
  final void Function(String? error) onSignedIn;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return OutlinedButton(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(double.infinity, 52),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
        side: BorderSide(
          color: Theme.of(context).colorScheme.outline,
        ),
      ),
      onPressed: isLoading ? null : () => _signIn(context, ref),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          // Google "G" icon — drawn as a coloured circle placeholder.
          // Replace with an SVG asset once google_sign_in is wired in.
          Container(
            width: 20,
            height: 20,
            decoration: const BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                colors: [Color(0xFF4285F4), Color(0xFF34A853)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
            ),
            child: const Center(
              child: Text(
                'G',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
          const SizedBox(width: 12),
          const Text('Continue with Google'),
        ],
      ),
    );
  }

  Future<void> _signIn(BuildContext context, WidgetRef ref) async {
    // Stub: returns null until google_sign_in is installed.
    // Replace with the real implementation described in the doc comment above.
    final idToken = await _getGoogleIdToken();

    if (idToken == null) {
      onSignedIn(
        'Google Sign-In is not configured. '
        'See the doc comment in _GoogleSignInButton for setup instructions.',
      );
      return;
    }

    final result =
        await ref.read(authStateProvider.notifier).googleSignIn(idToken);
    result.when(
      onSuccess: (_) => onSignedIn(null),
      onFailure: (err) => onSignedIn(err.userMessage),
    );
  }

  /// Returns a Google ID token, or null if the SDK is not wired.
  Future<String?> _getGoogleIdToken() async {
    // TODO: replace this stub with google_sign_in SDK call.
    // See class-level doc comment for instructions.
    return null;
  }
}
