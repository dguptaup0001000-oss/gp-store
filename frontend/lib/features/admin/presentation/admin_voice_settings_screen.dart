import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/notifications/push_notification_providers.dart';
import '../../../core/notifications/voice_announcement_providers.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';

/// On/off switch for spoken order announcements.
///
/// SCOPE, STATED ON THE SCREEN ITSELF because it is the thing a shopkeeper
/// would otherwise get wrong: this controls the VOICE only. Turning it off
/// leaves the notification, the banner and the auto-print exactly as they
/// are - the shop still learns about every order, just silently.
class AdminVoiceSettingsScreen extends ConsumerStatefulWidget {
  const AdminVoiceSettingsScreen({super.key});

  @override
  ConsumerState<AdminVoiceSettingsScreen> createState() => _AdminVoiceSettingsScreenState();
}

class _AdminVoiceSettingsScreenState extends ConsumerState<AdminVoiceSettingsScreen> {
  bool _isSaving = false;
  String? _statusMessage;

  Future<void> _setEnabled(bool enabled) async {
    setState(() {
      _isSaving = true;
      _statusMessage = null;
    });

    try {
      await ref.read(voiceSettingsProvider).setEnabled(enabled);
      // Re-read from storage rather than assuming the write took: the switch
      // should show what the app will actually do on the next order.
      ref.invalidate(voiceAnnouncementsEnabledProvider);
      if (!mounted) return;
      setState(() {
        _statusMessage = enabled
            ? 'New orders will be announced out loud.'
            : 'Announcements are off. Orders will still show a notification and still auto-print.';
      });
    } catch (e) {
      if (!mounted) return;
      // The setting did not stick, so the switch must not look as if it did.
      setState(() => _statusMessage = 'Could not save that setting, so it is unchanged. Try again: $e');
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final enabled = ref.watch(voiceAnnouncementsEnabledProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Order Announcements')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Container(
            decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
            child: SwitchListTile(
              value: enabled.valueOrNull ?? true,
              // Disabled only while a save is genuinely in flight, and while
              // the first read is still loading, so a tap can't race it.
              onChanged: hapticizeValueOrNull((_isSaving || enabled.isLoading) ? null : _setEnabled),
              title: const Text('Speak new orders aloud', style: TextStyle(fontWeight: FontWeight.w700)),
              subtitle: const Text(
                'Announces the customer name and the order amount, like a payment soundbox.',
                style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
              ),
              secondary: const Icon(Icons.campaign_outlined, color: AdminColors.primary),
            ),
          ),
          const SizedBox(height: 16),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
        color: AdminColors.surface,
        borderRadius: AdminRadius.card,
        border: Border.all(color: AdminColors.border),
        boxShadow: AdminShadows.card,
      ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Text('What gets said', style: TextStyle(fontWeight: FontWeight.w700)),
                SizedBox(height: 6),
                Text(
                  '"New order received from Ramesh Kumar. Order amount 520 rupees."',
                  style: TextStyle(color: AdminColors.textSecondary, fontSize: 13, fontStyle: FontStyle.italic),
                ),
                SizedBox(height: 10),
                Text(
                  'Only the name and the amount are ever spoken - never a phone number, '
                  'address, email or payment detail.',
                  style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
                ),
                SizedBox(height: 10),
                Text(
                  'Keep this app open and logged in as admin on the counter phone. '
                  'New orders ding and are spoken from the app itself, even when '
                  'Firebase push is not configured on this install.',
                  style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
                ),
                SizedBox(height: 10),
                Text(
                  'Announcements use the phone\'s own volume, and follow silent mode. '
                  'Each order is announced once, even if the notification arrives twice.',
                  style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
                ),
                SizedBox(height: 10),
                Text(
                  'Turning this off changes nothing else: notifications and receipt '
                  'printing carry on as normal.',
                  style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
                ),
              ],
            ),
          ),
          if (enabled.hasError) ...[
            const SizedBox(height: 12),
            const Text(
              'Could not read the saved setting, so announcements are treated as on.',
              style: TextStyle(color: AdminColors.textSecondary, fontSize: 13),
            ),
          ],
          if (_statusMessage != null) ...[
            const SizedBox(height: 12),
            Text(_statusMessage!, style: const TextStyle(color: AdminColors.textSecondary)),
          ],
          const SizedBox(height: 16),
          FilledButton.tonalIcon(
            onPressed: hapticize(() async {
              final id = DateTime.now().millisecondsSinceEpoch.toString();
              await ref.read(pushNotificationServiceProvider).alertNewOrder(
                    title: 'New order received from Test Customer',
                    body: 'Order amount ₹1',
                  );
              await ref.read(voiceAnnouncementServiceProvider).announceNewOrder(
                    orderId: 'test-$id',
                    customerName: 'Test Customer',
                    rupees: '1',
                  );
              if (!mounted) return;
              setState(() {
                _statusMessage =
                    'Played a test ding and spoken line. If you heard nothing, turn the media volume up and check notification permission.';
              });
            }),
            icon: const Icon(Icons.volume_up_outlined),
            label: const Text('Play a test announcement'),
          ),
        ],
      ),
    );
  }
}
