import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/haptic_widgets.dart';
import '../domain/shop_email.dart';
import 'otp_providers.dart';

class OtpLoginScreen extends ConsumerStatefulWidget {
  const OtpLoginScreen({super.key});

  @override
  ConsumerState<OtpLoginScreen> createState() => _OtpLoginScreenState();
}

class _OtpLoginScreenState extends ConsumerState<OtpLoginScreen> {
  final _phoneController = TextEditingController();
  final _otpController = TextEditingController();
  final _phoneFormKey = GlobalKey<FormState>();
  final _otpFormKey = GlobalKey<FormState>();
  final _otpFocus = FocusNode();

  @override
  void dispose() {
    _phoneController.dispose();
    _otpController.dispose();
    _otpFocus.dispose();
    super.dispose();
  }

  Future<void> _sendOtp() async {
    if (!_phoneFormKey.currentState!.validate()) return;

    final success = await ref
        .read(otpFlowControllerProvider.notifier)
        .sendOtp(_phoneController.text.trim());

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
    if (!_otpFormKey.currentState!.validate()) return;

    final success = await ref.read(otpFlowControllerProvider.notifier).verifyOtp(_otpController.text.trim());

    if (!mounted || success) return;
    _showError();
  }

  Future<void> _resend() async {
    _otpController.clear();
    final success = await ref.read(otpFlowControllerProvider.notifier).resendOtp();
    if (!mounted) return;
    if (success) {
      _otpFocus.requestFocus();
      return;
    }
    _showError();
  }

  void _showError() {
    final error = ref.read(otpFlowControllerProvider).errorMessage;
    if (error == null || error.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
  }

  @override
  Widget build(BuildContext context) {
    final otpState = ref.watch(otpFlowControllerProvider);
    final isPhoneStep = otpState.step == OtpFlowStep.enteringPhone;
    final isSendingOtp = otpState.step == OtpFlowStep.sendingOtp;
    final isVerifying = otpState.step == OtpFlowStep.verifying;
    final showOtpEntry = otpState.step == OtpFlowStep.otpSent || isVerifying;
    final masked = otpState.mobileNumber == null
        ? '****'
        : ShopEmail.mask(otpState.mobileNumber!);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Login with OTP'),
      ),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const CircleAvatar(
                  radius: 36,
                  backgroundColor: AppColors.primary,
                  child: Text('GP', style: TextStyle(color: Colors.white, fontSize: 28, fontWeight: FontWeight.w800)),
                ),
                const SizedBox(height: 12),
                Text(
                  'GP-Store',
                  style: Theme.of(context).textTheme.headlineMedium?.copyWith(color: AppColors.primary),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 28),
                if (isPhoneStep || isSendingOtp) ...[
                  Text(
                    'Log in with email',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    "We'll send you a one-time code",
                    style: Theme.of(context).textTheme.bodyMedium,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 32),
                  Form(
                    key: _phoneFormKey,
                    child: Semantics(
                      label: 'Email address',
                      textField: true,
                      child: TextFormField(
                        controller: _phoneController,
                        autofocus: true,
                        enabled: !isSendingOtp,
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: TextInputAction.done,
                        autofillHints: const [AutofillHints.email],
                        decoration: const InputDecoration(
                          labelText: 'Email',
                        ),
                        onFieldSubmitted: (_) => _sendOtp(),
                        validator: (value) {
                          if (value == null || value.isEmpty) return 'Email is required';
                          if (!ShopEmail.isValid(value)) {
                            return 'Please enter a valid email address.';
                          }
                          return null;
                        },
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: isSendingOtp ? null : hapticize(() { _sendOtp(); }),
                    child: isSendingOtp
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Text('Continue'),
                  ),
                ] else if (showOtpEntry) ...[
                  Text(
                    'Enter the OTP sent to $masked',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 32),
                  Form(
                    key: _otpFormKey,
                    child: Semantics(
                      label: 'Six digit one time password',
                      textField: true,
                      child: TextFormField(
                        controller: _otpController,
                        focusNode: _otpFocus,
                        keyboardType: TextInputType.number,
                        textInputAction: TextInputAction.done,
                        autofillHints: const [AutofillHints.oneTimeCode],
                        maxLength: 6,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 24, letterSpacing: 8),
                        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                        decoration: const InputDecoration(labelText: '6-digit code', counterText: ''),
                        onChanged: (value) {
                          if (value.length == 6 && !isVerifying) {
                            _verifyOtp();
                          }
                        },
                        onFieldSubmitted: (_) => _verifyOtp(),
                        validator: (value) {
                          if (value == null || value.length != 6) return 'Enter the 6-digit code';
                          return null;
                        },
                      ),
                    ),
                  ),
                  if (otpState.errorMessage != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      otpState.errorMessage!,
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                  ],
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: isVerifying ? null : hapticize(() { _verifyOtp(); }),
                    child: isVerifying
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : const Text('Verify & continue'),
                  ),
                  const SizedBox(height: 12),
                  if (otpState.resendSecondsRemaining > 0)
                    Text(
                      'Resend code in ${otpState.resendSecondsRemaining}s',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium,
                    )
                  else
                    TextButton(
                      onPressed: isVerifying ? null : hapticize(() { _resend(); }),
                      child: const Text('Resend OTP'),
                    ),
                  TextButton(
                    onPressed: isVerifying
                        ? null
                        : hapticize(() {
                            _otpController.clear();
                            ref.read(otpFlowControllerProvider.notifier).resetToPhoneEntry();
                          }),
                    child: const Text('Use a different email'),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
