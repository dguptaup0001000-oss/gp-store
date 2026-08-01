import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';

class ForgotPasswordScreen extends ConsumerStatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  ConsumerState<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen> {
  final _mobileController = TextEditingController();
  final _otpController = TextEditingController();
  final _newPasswordController = TextEditingController();

  bool _otpSent = false;
  bool _isSending = false;
  bool _isResetting = false;

  @override
  void dispose() {
    _mobileController.dispose();
    _otpController.dispose();
    _newPasswordController.dispose();
    super.dispose();
  }

  Future<void> _sendOtp() async {
    final mobile = _mobileController.text.trim();
    if (!RegExp(r'^[6-9]\d{9}$').hasMatch(mobile)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Enter a valid 10-digit mobile number')));
      return;
    }

    setState(() => _isSending = true);

    try {
      await ref.read(authRepositoryProvider).sendOtp(mobileNumber: mobile);
      if (!mounted) return;
      setState(() => _otpSent = true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isSending = false);
    }
  }

  Future<void> _resetPassword() async {
    if (_otpController.text.length != 6) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Enter the 6-digit code')));
      return;
    }
    if (_newPasswordController.text.length < 8) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Password must be at least 8 characters')));
      return;
    }

    setState(() => _isResetting = true);

    try {
      await ref.read(authRepositoryProvider).resetPasswordWithOtp(
            mobileNumber: _mobileController.text.trim(),
            otp: _otpController.text.trim(),
            newPassword: _newPasswordController.text,
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Password reset - you can now log in with your new password')),
      );
      Navigator.of(context).pop();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isResetting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Reset Password')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                _otpSent
                    ? 'Enter the code sent to +91 ${_mobileController.text.trim()}'
                    : "Enter your account's mobile number - we'll send a code to reset your password",
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 24),
              TextField(
                controller: _mobileController,
                enabled: !_otpSent,
                keyboardType: TextInputType.phone,
                maxLength: 10,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: const InputDecoration(labelText: 'Mobile number', prefixText: '+91 ', counterText: ''),
              ),
              if (!_otpSent) ...[
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _isSending ? null : _sendOtp,
                  child: _isSending
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Send Code'),
                ),
              ] else ...[
                const SizedBox(height: 16),
                TextField(
                  controller: _otpController,
                  keyboardType: TextInputType.number,
                  maxLength: 6,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 22, letterSpacing: 6),
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: const InputDecoration(labelText: '6-digit code', counterText: ''),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _newPasswordController,
                  obscureText: true,
                  decoration: const InputDecoration(labelText: 'New password'),
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: _isResetting ? null : _resetPassword,
                  child: _isResetting
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Reset Password'),
                ),
                TextButton(
                  onPressed: _isSending ? null : _sendOtp,
                  child: const Text('Resend code'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
