import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../auth/presentation/auth_providers.dart';
import 'admin_providers.dart';
import '../../../core/util/haptic_widgets.dart';

class AdminBroadcastScreen extends ConsumerStatefulWidget {
  const AdminBroadcastScreen({super.key});

  @override
  ConsumerState<AdminBroadcastScreen> createState() => _AdminBroadcastScreenState();
}

class _AdminBroadcastScreenState extends ConsumerState<AdminBroadcastScreen> {
  final _titleController = TextEditingController();
  final _messageController = TextEditingController();
  bool _isSending = false;

  @override
  void dispose() {
    _titleController.dispose();
    _messageController.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (_titleController.text.trim().isEmpty || _messageController.text.trim().isEmpty) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Send to every customer?'),
        content: const Text('This creates a notification for every active customer account. This cannot be undone.'),
        actions: [
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(false)), child: const Text('Cancel')),
          TextButton(onPressed: hapticize(() => Navigator.of(context).pop(true)), child: const Text('Send')),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _isSending = true);

    try {
      final result = await ref.read(adminProductsRepositoryProvider).broadcastNotification(
            title: _titleController.text.trim(),
            message: _messageController.text.trim(),
          );
      if (!mounted) return;
      _titleController.clear();
      _messageController.clear();
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(result)));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(extractErrorMessage(e))));
    } finally {
      if (mounted) setState(() => _isSending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Broadcast Notification')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text(
                'Sends a real, individual notification to every active customer account - not a preview or a queued draft.',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 16),
              TextField(controller: _titleController, decoration: const InputDecoration(labelText: 'Title')),
              const SizedBox(height: 12),
              TextField(
                controller: _messageController,
                maxLines: 4,
                decoration: const InputDecoration(labelText: 'Message'),
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: _isSending ? null : _send,
                child: _isSending
                    ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : const Text('Send to All Customers'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
