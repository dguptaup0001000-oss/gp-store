import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/util/haptic_widgets.dart';
import '../domain/otp_user_messages.dart';
import '../domain/shop_email.dart';
import 'password_reset_providers.dart';

class ForgotPasswordScreen extends ConsumerStatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  ConsumerState<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen> {
  final _mobileController = TextEditingController();
  final _otpController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  final _otpFocus = FocusNode();
  bool _obscureNew = true;
  bool _obscureConfirm = true;

  @override
  void dispose() {
    _mobileController.dispose();
    _otpController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    _otpFocus.dispose();
    super.dispose();
  }

  Future<void> _sendOtp() async {
    final success = await ref.read(passwordResetControllerProvider.notifier).requestOtp(_mobileController.text);
    if (!mounted) return;
    if (success) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _otpFocus.requestFocus();
      });
      return;
    }
    _showError();
  }

  Future<void> _verifyOtp() async {
    if (_otpController.text.length != 6) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Enter the 6-digit code')));
      return;
    }
    final success = await ref.read(passwordResetControllerProvider.notifier).verifyOtp(_otpController.text.trim());
    if (!mounted || success) return;
    _showError();
  }

  Future<void> _complete() async {
    final success = await ref.read(passwordResetControllerProvider.notifier).complete(
          newPassword: _newPasswordController.text,
          confirmPassword: _confirmPasswordController.text,
        );
    if (!mounted) return;
    if (success) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text(OtpUserMessages.resetSuccess)),
      );
      Navigator.of(context).pop();
      return;
    }
    _showError();
  }

  void _showError() {
    final error = ref.read(passwordResetControllerProvider).errorMessage;
    if (error == null || error.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(passwordResetControllerProvider);
    final sending = state.step == PasswordResetStep.sendingOtp;
    final verifying = state.step == PasswordResetStep.verifyingOtp;
    final completing = state.step == PasswordResetStep.completing;
    final phoneStep = state.step == PasswordResetStep.enteringPhone || sending;
    final otpStep = state.step == PasswordResetStep.otpSent || verifying;
    final passwordStep = state.step == PasswordResetStep.settingPassword || completing;
    final masked = state.mobileNumber == null ? '****' : ShopEmail.mask(state.mobileNumber!);

    return Scaffold(
      appBar: AppBar(title: const Text('Reset Password')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                phoneStep
                    ? "Enter your account's email. If it is eligible, we'll send a reset code."
                    : otpStep
                        ? 'Enter the OTP sent to $masked'
                        : 'Choose a new password',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 24),
              if (phoneStep) ...[
                Semantics(
                  label: 'Email address',
                  textField: true,
                  child: TextField(
                    controller: _mobileController,
                    autofocus: true,
                    enabled: !sending,
                    keyboardType: TextInputType.emailAddress,
                    autofillHints: const [AutofillHints.email],
                    decoration: const InputDecoration(
                      labelText: 'Email',
                    ),
                    onSubmitted: (_) => _sendOtp(),
                  ),
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: sending ? null : hapticize(() { _sendOtp(); }),
                  child: sending
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Send OTP'),
                ),
              ] else if (otpStep) ...[
                Semantics(
                  label: 'Six digit one time password',
                  textField: true,
                  child: TextField(
                    controller: _otpController,
                    focusNode: _otpFocus,
                    keyboardType: TextInputType.number,
                    autofillHints: const [AutofillHints.oneTimeCode],
                    maxLength: 6,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 22, letterSpacing: 6),
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    decoration: const InputDecoration(labelText: '6-digit code', counterText: ''),
                    onChanged: (value) {
                      if (value.length == 6 && !verifying) _verifyOtp();
                    },
                  ),
                ),
                if (state.errorMessage != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    state.errorMessage!,
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: verifying ? null : hapticize(() { _verifyOtp(); }),
                  child: verifying
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Verify'),
                ),
                const SizedBox(height: 8),
                if (state.resendSecondsRemaining > 0)
                  Text(
                    'Resend code in ${state.resendSecondsRemaining}s',
                    textAlign: TextAlign.center,
                  )
                else
                  TextButton(
                    onPressed: verifying ? null : hapticize(() {
                      _otpController.clear();
                      ref.read(passwordResetControllerProvider.notifier).resendOtp();
                    }),
                    child: const Text('Resend OTP'),
                  ),
                TextButton(
                  onPressed: verifying
                      ? null
                      : hapticize(() {
                          _otpController.clear();
                          ref.read(passwordResetControllerProvider.notifier).backToPhone();
                        }),
                  child: const Text('Use a different number'),
                ),
              ] else if (passwordStep) ...[
                TextField(
                  controller: _newPasswordController,
                  obscureText: _obscureNew,
                  autofillHints: const [AutofillHints.newPassword],
                  decoration: InputDecoration(
                    labelText: 'New password',
                    suffixIcon: IconButton(
                      icon: Icon(_obscureNew ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                      onPressed: hapticize(() => setState(() => _obscureNew = !_obscureNew)),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _confirmPasswordController,
                  obscureText: _obscureConfirm,
                  decoration: InputDecoration(
                    labelText: 'Confirm password',
                    suffixIcon: IconButton(
                      icon: Icon(_obscureConfirm ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                      onPressed: hapticize(() => setState(() => _obscureConfirm = !_obscureConfirm)),
                    ),
                  ),
                ),
                if (state.errorMessage != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    state.errorMessage!,
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ],
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: completing ? null : hapticize(() { _complete(); }),
                  child: completing
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Reset password'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
