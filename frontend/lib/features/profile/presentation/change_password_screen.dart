import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/domain/password_policy.dart';
import '../../auth/presentation/auth_providers.dart';

/// Change-password form, in two modes.
///
/// The ordinary mode is reached from the profile screen: there is a way back,
/// and somebody who has forgotten their current password can simply leave.
///
/// [forced] is the mode an account lands in when the platform opened it and
/// handed over a ONE-TIME password. The backend refuses every other route
/// while that is true (see JwtFilter), so this screen is the whole app until
/// the operator sets their own password - there is nothing to go back to, and
/// no Navigator entry beneath it. In that mode the current password is
/// REQUIRED, because a one-time password always exists, and success reports
/// through [onChanged] rather than popping.
class ChangePasswordScreen extends ConsumerStatefulWidget {
  const ChangePasswordScreen({
    super.key,
    this.forced = false,
    this.onChanged,
    this.onSignOut,
  });

  final bool forced;

  /// Called instead of popping, once the new password is saved.
  final VoidCallback? onChanged;

  /// The way out for somebody holding the wrong account's slip. Without it a
  /// forced change is a dead end: no back button, and every other route
  /// refused.
  final VoidCallback? onSignOut;

  @override
  ConsumerState<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends ConsumerState<ChangePasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _currentPasswordController = TextEditingController();
  final _newPasswordController = TextEditingController();
  bool _isSaving = false;

  @override
  void dispose() {
    _currentPasswordController.dispose();
    _newPasswordController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSaving = true);

    try {
      await ref.read(authRepositoryProvider).changePassword(
            currentPassword: _currentPasswordController.text.isEmpty ? null : _currentPasswordController.text,
            newPassword: _newPasswordController.text,
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Password updated')));
      if (widget.forced) {
        // NOT pop(). This screen was shown in place of the app, not pushed
        // over it, so there is nothing underneath to return to.
        widget.onChanged?.call();
      } else {
        Navigator.of(context).pop();
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    // canPop:false, not just a hidden back button. Android's system back
    // gesture does not go through the AppBar, and a forced change that the
    // hardware back button escapes is not a gate.
    return PopScope(
      canPop: !widget.forced,
      child: _form(context),
    );
  }

  Widget _form(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.forced ? 'Set your password' : 'Change Password'),
        automaticallyImplyLeading: !widget.forced,
        actions: [
          if (widget.forced && widget.onSignOut != null)
            TextButton(
              onPressed: widget.onSignOut,
              child: const Text('Sign out'),
            ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (widget.forced) ...[
                  Text(
                    'This account is still on the one-time password it was '
                    'created with. Set a password only you know before using '
                    'the app.',
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 16),
                ],
                TextFormField(
                  controller: _currentPasswordController,
                  obscureText: true,
                  decoration: InputDecoration(
                    labelText: widget.forced
                        ? 'One-time password'
                        : 'Current password',
                    helperText: widget.forced
                        ? 'The password you were given when this account was opened'
                        : "Leave blank if you don't have one yet (e.g. you signed up via OTP)",
                    helperMaxLines: 2,
                  ),
                  // Required in forced mode only. A one-time password always
                  // exists there, and sending null would be refused by the
                  // backend anyway - better to say so before the round trip.
                  validator: widget.forced
                      ? (value) => (value == null || value.isEmpty)
                          ? 'Enter the one-time password you were given'
                          : null
                      : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _newPasswordController,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'New password',
                    helperText: AppPasswordPolicy.helperText,
                    helperMaxLines: 2,
                  ),
                  validator: AppPasswordPolicy.validateNewPassword,
                ),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _isSaving ? null : _save,
                  child: _isSaving
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Save'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
