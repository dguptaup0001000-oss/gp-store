import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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

  @override
  void dispose() {
    _phoneController.dispose();
    _otpController.dispose();
    super.dispose();
  }

  Future<void> _sendOtp() async {
    if (!_phoneFormKey.currentState!.validate()) return;

    final success = await ref
        .read(otpFlowControllerProvider.notifier)
        .sendOtp(_phoneController.text.trim());

    if (!mounted || success) return;
    _showError();
  }

  Future<void> _verifyOtp() async {
    if (!_otpFormKey.currentState!.validate()) return;

    final success = await ref.read(otpFlowControllerProvider.notifier).verifyOtp(_otpController.text.trim());

    // On success, AuthController's state change triggers the router to
    // redirect automatically (see app_router.dart) - no manual navigation here.
    if (!mounted || success) return;
    _showError();
  }

  void _showError() {
    final error = ref.read(otpFlowControllerProvider).errorMessage;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(error ?? 'Something went wrong. Please try again.')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final otpState = ref.watch(otpFlowControllerProvider);
    final isPhoneStep = otpState.step == OtpFlowStep.enteringPhone;
    final isSendingOtp = otpState.step == OtpFlowStep.sendingOtp;
    final isVerifying = otpState.step == OtpFlowStep.verifying;
    final showOtpEntry = otpState.step == OtpFlowStep.otpSent || isVerifying;

    return Scaffold(
      appBar: AppBar(),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (isPhoneStep || isSendingOtp) ...[
                  Text(
                    'Log in with mobile number',
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
                    child: TextFormField(
                      controller: _phoneController,
                      keyboardType: TextInputType.phone,
                      maxLength: 10,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: const InputDecoration(
                        labelText: 'Mobile number',
                        prefixText: '+91 ',
                        counterText: '',
                      ),
                      // Mirrors the backend's exact validation pattern
                      // (^[6-9]\d{9}$) - catches an invalid number instantly
                      // instead of after a round-trip to the server.
                      validator: (value) {
                        if (value == null || value.isEmpty) return 'Mobile number is required';
                        if (!RegExp(r'^[6-9]\d{9}$').hasMatch(value)) {
                          return 'Enter a valid 10-digit mobile number';
                        }
                        return null;
                      },
                    ),
                  ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: isSendingOtp ? null : _sendOtp,
                    child: isSendingOtp
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Send OTP'),
                  ),
                ] else if (showOtpEntry) ...[
                  Text(
                    'Enter the code',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Sent to +91 ${otpState.mobileNumber}',
                    style: Theme.of(context).textTheme.bodyMedium,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 32),
                  Form(
                    key: _otpFormKey,
                    child: TextFormField(
                      controller: _otpController,
                      keyboardType: TextInputType.number,
                      maxLength: 6,
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 24, letterSpacing: 8),
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      decoration: const InputDecoration(labelText: '6-digit code', counterText: ''),
                      validator: (value) {
                        if (value == null || value.length != 6) return 'Enter the 6-digit code';
                        return null;
                      },
                    ),
                  ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: isVerifying ? null : _verifyOtp,
                    child: isVerifying
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Verify & continue'),
                  ),
                  const SizedBox(height: 12),
                  TextButton(
                    onPressed: isVerifying
                        ? null
                        : () {
                            _otpController.clear();
                            ref.read(otpFlowControllerProvider.notifier).resetToPhoneEntry();
                          },
                    child: const Text('Use a different number'),
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
