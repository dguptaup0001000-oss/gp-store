import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_theme.dart';

import 'auth_providers.dart';
import 'forgot_password_screen.dart';
import '../../../core/util/haptic_widgets.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isSubmitting = false;
  bool _obscurePassword = true;
  bool _rememberMe = true;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    // Fires the moment the tap registers as a valid attempt, not after the
    // network round-trip resolves - AuthController.login() still fires its
    // own haptic on confirmed success, this one is the immediate "the app
    // felt that" response every tap should have.
    HapticFeedback.mediumImpact();
    setState(() => _isSubmitting = true);

    final success = await ref.read(authControllerProvider.notifier).login(
          email: _emailController.text.trim(),
          password: _passwordController.text,
          rememberMe: _rememberMe,
        );

    if (!mounted) return;
    setState(() => _isSubmitting = false);

    // On success, the router (app_router.dart) reacts to the auth state
    // change automatically and redirects - no manual navigation needed here.
    if (!success) {
      final error = ref.read(authControllerProvider).errorMessage;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(error ?? 'Login failed. Please try again.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Soft decorative gradient wash behind everything - purely
          // cosmetic, gives the plain white background some depth instead
          // of feeling like a bare form.
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  center: Alignment.topLeft,
                  radius: 1.2,
                  colors: [
                    AppColors.primary.withValues(alpha: 0.10),
                    AppColors.background,
                  ],
                ),
              ),
            ),
          ),
          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: Form(
                  key: _formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Container(
                        width: 84,
                        height: 84,
                        alignment: Alignment.center,
                        decoration: const BoxDecoration(color: AppColors.primary, shape: BoxShape.circle),
                        child: const Text(
                          'GP',
                          style: TextStyle(color: Colors.white, fontSize: 32, fontWeight: FontWeight.w800),
                        ),
                      ),
                      const SizedBox(height: 14),
                      Text(
                        'GP-Store',
                        style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                              color: AppColors.primary,
                              fontSize: 30,
                              fontWeight: FontWeight.w800,
                            ),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Your neighbourhood store, delivered',
                        style: Theme.of(context).textTheme.bodyMedium,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 20),

                      _TrustBadgesBar(),

                      const SizedBox(height: 20),

                      Container(
                        padding: const EdgeInsets.all(20),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(AppRadius.lg),
                          boxShadow: [
                            BoxShadow(color: AppColors.textPrimary.withValues(alpha: 0.05), blurRadius: 20, offset: const Offset(0, 8)),
                          ],
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Welcome back!', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontSize: 20)),
                            const SizedBox(height: 2),
                            Text('Login to continue shopping', style: Theme.of(context).textTheme.bodyMedium),
                            const SizedBox(height: 20),

                            const Text('Email', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                            const SizedBox(height: 6),
                            TextFormField(
                              controller: _emailController,
                              keyboardType: TextInputType.emailAddress,
                              decoration: InputDecoration(
                                hintText: 'Enter your email',
                                prefixIcon: Padding(
                                  padding: const EdgeInsets.all(10),
                                  child: CircleAvatar(
                                    radius: 12,
                                    backgroundColor: AppColors.primary.withValues(alpha: 0.12),
                                    child: const Icon(Icons.mail_outline, size: 15, color: AppColors.primary),
                                  ),
                                ),
                              ),
                              validator: (value) {
                                if (value == null || value.isEmpty) return 'Email is required';
                                if (!value.contains('@')) return 'Enter a valid email';
                                return null;
                              },
                            ),
                            const SizedBox(height: 16),

                            const Text('Password', style: TextStyle(fontWeight: FontWeight.w600, fontSize: 13)),
                            const SizedBox(height: 6),
                            TextFormField(
                              controller: _passwordController,
                              obscureText: _obscurePassword,
                              decoration: InputDecoration(
                                hintText: 'Enter your password',
                                prefixIcon: Padding(
                                  padding: const EdgeInsets.all(10),
                                  child: CircleAvatar(
                                    radius: 12,
                                    backgroundColor: AppColors.primary.withValues(alpha: 0.12),
                                    child: const Icon(Icons.lock_outline, size: 15, color: AppColors.primary),
                                  ),
                                ),
                                suffixIcon: IconButton(
                                  icon: Icon(_obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                                  onPressed: hapticize(() => setState(() => _obscurePassword = !_obscurePassword)),
                                ),
                              ),
                              validator: (value) {
                                if (value == null || value.isEmpty) return 'Password is required';
                                return null;
                              },
                            ),
                            const SizedBox(height: 10),

                            Row(
                              children: [
                                SizedBox(
                                  height: 24,
                                  width: 24,
                                  child: Checkbox(
                                    value: _rememberMe,
                                    onChanged: hapticizeValue((value) => setState(() => _rememberMe = value ?? true)),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                const Text('Remember me', style: TextStyle(fontSize: 13)),
                                const Spacer(),
                                TextButton(
                                  style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: Size.zero),
                                  onPressed: hapticize(() => Navigator.of(context).push(
                                    MaterialPageRoute(builder: (_) => const ForgotPasswordScreen()),
                                  )),
                                  child: const Text('Forgot password?', style: TextStyle(fontSize: 13)),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),

                            FilledButton(
                              onPressed: _isSubmitting ? null : _submit,
                              child: _isSubmitting
                                  ? const SizedBox(
                                      height: 20,
                                      width: 20,
                                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                                    )
                                  : const Row(
                                      mainAxisAlignment: MainAxisAlignment.center,
                                      children: [
                                        Text('Log in'),
                                        SizedBox(width: 8),
                                        Icon(Icons.arrow_forward, size: 18),
                                      ],
                                    ),
                            ),
                            const SizedBox(height: 16),

                            Row(
                              children: [
                                const Expanded(child: Divider()),
                                Padding(
                                  padding: const EdgeInsets.symmetric(horizontal: 10),
                                  child: Text('or continue with', style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12)),
                                ),
                                const Expanded(child: Divider()),
                              ],
                            ),
                            const SizedBox(height: 16),

                            OutlinedButton.icon(
                              onPressed: hapticize(() => context.push('/login/otp')),
                              icon: const Icon(Icons.phone_android_outlined, size: 18),
                              label: const Text('Login with Mobile OTP'),
                              style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(46)),
                            ),
                          ],
                        ),
                      ),

                      const SizedBox(height: 16),

                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: AppColors.primary.withValues(alpha: 0.06),
                          borderRadius: BorderRadius.circular(AppRadius.lg),
                        ),
                        child: Row(
                          children: [
                            CircleAvatar(
                              radius: 20,
                              backgroundColor: AppColors.primary.withValues(alpha: 0.15),
                              child: const Icon(Icons.shopping_basket_outlined, color: AppColors.primary),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text("Don't have an account?", style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                                  Text('Create a new account and get started', style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12)),
                                ],
                              ),
                            ),
                            const SizedBox(width: 8),
                            FilledButton.tonalIcon(
                              onPressed: hapticize(() => context.push('/register')),
                              icon: const Icon(Icons.arrow_forward, size: 16),
                              label: const Text('Register'),
                              style: FilledButton.styleFrom(
                                backgroundColor: Colors.white,
                                foregroundColor: AppColors.primary,
                                minimumSize: Size.zero,
                                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                              ),
                            ),
                          ],
                        ),
                      ),

                      const SizedBox(height: 20),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(Icons.verified_user_outlined, size: 15, color: AppColors.success),
                          const SizedBox(width: 6),
                          Text('Your data is safe with us', style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 12)),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The "Delivery on Time / Best Quality / Secure Payment" trust-badges row -
/// purely reassurance copy shown above the login form, same claims already
/// used elsewhere in the app's marketing surfaces.
class _TrustBadgesBar extends StatelessWidget {
  const _TrustBadgesBar();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(AppRadius.lg),
        boxShadow: [
          BoxShadow(color: AppColors.textPrimary.withValues(alpha: 0.04), blurRadius: 12, offset: const Offset(0, 4)),
        ],
      ),
      child: const Row(
        children: [
          Expanded(child: _TrustBadge(icon: Icons.access_time, title: 'Delivery on Time', subtitle: 'On time, every time')),
          VerticalDivider(width: 1, indent: 6, endIndent: 6),
          Expanded(child: _TrustBadge(icon: Icons.verified_outlined, title: 'Best Quality', subtitle: 'Products you can trust')),
          VerticalDivider(width: 1, indent: 6, endIndent: 6),
          Expanded(child: _TrustBadge(icon: Icons.shield_outlined, title: 'Secure Payment', subtitle: '100% Safe & Secure')),
        ],
      ),
    );
  }
}

class _TrustBadge extends StatelessWidget {
  const _TrustBadge({required this.icon, required this.title, required this.subtitle});

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6),
      child: Column(
        children: [
          Icon(icon, color: AppColors.primary, size: 20),
          const SizedBox(height: 6),
          Text(title, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 11)),
          Text(subtitle, textAlign: TextAlign.center, style: const TextStyle(fontSize: 9, color: AppColors.textSecondary)),
        ],
      ),
    );
  }
}
