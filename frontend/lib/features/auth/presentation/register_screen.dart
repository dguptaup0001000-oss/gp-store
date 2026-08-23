import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'auth_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _emailController = TextEditingController();
  final _phoneController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isSubmitting = false;

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    _phoneController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    HapticFeedback.mediumImpact();
    setState(() => _isSubmitting = true);

    final success = await ref.read(authControllerProvider.notifier).register(
          name: _nameController.text.trim(),
          email: _emailController.text.trim(),
          phone: _phoneController.text.trim(),
          password: _passwordController.text,
        );

    if (!mounted) return;
    setState(() => _isSubmitting = false);

    if (!success) {
      final error = ref.read(authControllerProvider).errorMessage;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(error ?? 'Registration failed. Please try again.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Create account')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  controller: _nameController,
                  decoration: const InputDecoration(labelText: 'Full name'),
                  validator: (value) =>
                      (value == null || value.trim().isEmpty) ? 'Name is required' : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _emailController,
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(labelText: 'Email'),
                  validator: (value) {
                    if (value == null || value.isEmpty) return 'Email is required';
                    if (!value.contains('@')) return 'Enter a valid email';
                    return null;
                  },
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _phoneController,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(labelText: 'Mobile number'),
                  validator: (value) =>
                      (value == null || value.trim().isEmpty) ? 'Phone number is required' : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: _passwordController,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Password',
                    helperText: 'At least 8 characters',
                  ),
                  // Mirrors the backend's @Size(min = 8) constraint exactly -
                  // catching this here means the user finds out instantly
                  // instead of after a round-trip to the server.
                  validator: (value) {
                    if (value == null || value.isEmpty) return 'Password is required';
                    if (value.length < 8) return 'Password must be at least 8 characters';
                    return null;
                  },
                ),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _isSubmitting ? null : _submit,
                  child: _isSubmitting
                      ? const SizedBox(
                          height: 20,
                          width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Create account'),
                ),
                const SizedBox(height: 12),
                TextButton(
                  onPressed: hapticize(() => context.pop()),
                  child: const Text('Already have an account? Log in'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
