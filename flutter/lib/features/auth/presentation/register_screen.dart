/// Registration screen — email, display name, password (≥12 chars).
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

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _formKey      = GlobalKey<FormState>();
  final _nameCtrl     = TextEditingController();
  final _emailCtrl    = TextEditingController();
  final _passCtrl     = TextEditingController();
  final _confirmCtrl  = TextEditingController();
  bool  _obscurePass  = true;
  String? _errorMessage;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _emailCtrl.dispose();
    _passCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    setState(() => _errorMessage = null);

    final result = await ref.read(authStateProvider.notifier).register(
          RegisterRequest(
            email:       _emailCtrl.text.trim(),
            password:    _passCtrl.text,
            displayName: _nameCtrl.text.trim(),
          ),
        );

    result.when(
      onSuccess: (_) {},
      onFailure: (err) {
        if (mounted) setState(() => _errorMessage = err.userMessage);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isLoading = ref.watch(authStateProvider).isLoading;

    return Scaffold(
      appBar: AppBar(
        leading: BackButton(onPressed: () => context.go(Routes.login)),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 16),
                Text('Create account', style: context.texts.headlineMedium),
                const SizedBox(height: 8),
                Text(
                  'Join AI Assistant to get started',
                  style: context.texts.bodyMedium
                      ?.copyWith(color: context.mutedColor),
                ),
                const SizedBox(height: 32),

                // Display name
                AppTextField(
                  controller: _nameCtrl,
                  label: 'Display name',
                  hint: 'Your name',
                  textInputAction: TextInputAction.next,
                  enabled: !isLoading,
                ),
                const SizedBox(height: 16),

                // Email
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

                // Password
                AppTextField(
                  controller: _passCtrl,
                  label: 'Password',
                  hint: 'Min. 12 characters',
                  obscureText: _obscurePass,
                  textInputAction: TextInputAction.next,
                  enabled: !isLoading,
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
                    if (v.length < 12) {
                      return 'Password must be at least 12 characters';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                // Confirm password
                AppTextField(
                  controller: _confirmCtrl,
                  label: 'Confirm password',
                  hint: 'Repeat password',
                  obscureText: _obscurePass,
                  textInputAction: TextInputAction.done,
                  enabled: !isLoading,
                  onSubmitted: (_) => _submit(),
                  validator: (v) {
                    if (v != _passCtrl.text) {
                      return 'Passwords do not match';
                    }
                    return null;
                  },
                ),

                if (_errorMessage != null) ...[
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: context.critical.withAlpha(20),
                      borderRadius: BorderRadius.circular(12),
                      border:
                          Border.all(color: context.critical.withAlpha(60)),
                    ),
                    child: Text(
                      _errorMessage!,
                      style: context.texts.bodySmall
                          ?.copyWith(color: context.critical),
                    ),
                  ),
                ],

                const SizedBox(height: 24),

                AppButton(
                  label: 'Create account',
                  isLoading: isLoading,
                  onPressed: _submit,
                ),

                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text('Already have an account? ',
                        style: context.texts.bodyMedium),
                    TextButton(
                      onPressed:
                          isLoading ? null : () => context.go(Routes.login),
                      child: const Text('Sign in'),
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
