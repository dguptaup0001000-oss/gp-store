import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../admin/design/admin_components.dart';
import '../../../admin/design/admin_tokens.dart';
import '../../../core/util/haptic_widgets.dart';
import '../../auth/presentation/auth_providers.dart';
import '../../returns/domain/return_models.dart';
import '../../returns/presentation/returns_providers.dart';

/// The returns queue: what customers have sent back, waiting on a decision.
///
/// APPROVING SENDS MONEY. It puts the stock back and opens a refund through
/// the ledger, in one transaction - so the confirmation dialog names the
/// amount and says plainly that it cannot be undone. The figure shown is the
/// server's own: it comes back on the return, computed from the order's line
/// prices, and this screen never calculates or sends one.
///
/// OLDEST FIRST, from the backend. A queue worked newest-first leaves the
/// customer who asked on Monday still waiting on Friday, and they are the one
/// most likely to ring.
class AdminReturnsScreen extends ConsumerWidget {
  const AdminReturnsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pending = ref.watch(pendingReturnsProvider);

    return Scaffold(
      backgroundColor: AdminColors.background,
      appBar: AppBar(title: const Text('Returns')),
      body: pending.when(
        loading: () => const AdminListSkeleton(),
        error: (error, _) => AdminErrorState(
          message: "Couldn't load returns: ${extractErrorMessage(error)}",
          onRetry: hapticize(() => ref.invalidate(pendingReturnsProvider)),
        ),
        data: (requests) {
          if (requests.isEmpty) {
            return const AdminEmptyState(
              icon: Icons.assignment_return_outlined,
              title: 'Nothing waiting',
              message: 'Return requests from customers appear here.',
            );
          }
          return RefreshIndicator(
            onRefresh: () async {
              ref.invalidate(pendingReturnsProvider);
            },
            child: ListView.separated(
              padding: const EdgeInsets.all(AdminSpacing.lg),
              itemCount: requests.length,
              separatorBuilder: (_, __) => const SizedBox(height: AdminSpacing.md),
              itemBuilder: (context, index) => _ReturnCard(request: requests[index]),
            ),
          );
        },
      ),
    );
  }
}

class _ReturnCard extends ConsumerStatefulWidget {
  const _ReturnCard({required this.request});

  final ReturnRequest request;

  @override
  ConsumerState<_ReturnCard> createState() => _ReturnCardState();
}

class _ReturnCardState extends ConsumerState<_ReturnCard> {
  bool _isDeciding = false;

  double get _askedFor {
    double total = 0;
    for (final line in widget.request.items) {
      total += line.lineTotal;
    }
    return total;
  }

  Future<void> _approve() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Take these items back?'),
        content: Text(
          'About ₹${_askedFor.toStringAsFixed(0)} goes back to the customer and '
          'the stock returns to the shelf. This cannot be undone.',
        ),
        actions: [
          TextButton(
              onPressed: hapticize(() => Navigator.of(context).pop(false)),
              child: const Text('Not yet')),
          FilledButton(
              onPressed: hapticize(() => Navigator.of(context).pop(true)),
              child: const Text('Approve')),
        ],
      ),
    );
    if (confirmed != true) return;
    await _run(() => ref.read(returnsRepositoryProvider).approve(widget.request.id));
  }

  Future<void> _reject() async {
    final controller = TextEditingController();
    try {
      final note = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Refuse this return'),
          content: TextField(
            controller: controller,
            maxLines: 3,
            maxLength: 500,
            autofocus: true,
            decoration: const InputDecoration(
              labelText: 'Why?',
              // The customer reads this. A refusal with no reason is how
              // somebody decides the shop is dishonest.
              helperText: 'The customer will see this.',
            ),
          ),
          actions: [
            TextButton(
                onPressed: hapticize(() => Navigator.of(context).pop()),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: hapticize(
                    () => Navigator.of(context).pop(controller.text.trim())),
                child: const Text('Refuse')),
          ],
        ),
      );
      if (note == null || note.isEmpty) return;
      await _run(
          () => ref.read(returnsRepositoryProvider).reject(widget.request.id, note));
    } finally {
      controller.dispose();
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _isDeciding = true);
    try {
      await action();
      ref.invalidate(pendingReturnsProvider);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(extractErrorMessage(e))),
      );
    } finally {
      if (mounted) setState(() => _isDeciding = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final request = widget.request;

    return AdminSectionCard(
      title: '#${request.orderNumber ?? request.orderId ?? ''}',
      subtitle: '₹${_askedFor.toStringAsFixed(0)} asked back',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final line in request.items)
            Padding(
              padding: const EdgeInsets.only(bottom: AdminSpacing.xs),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      line.pack == null
                          ? line.productName
                          : '${line.productName} · ${line.pack}',
                      style: AdminText.body,
                    ),
                  ),
                  Text('× ${line.quantity}', style: AdminText.numeric),
                  const SizedBox(width: AdminSpacing.md),
                  Text('₹${line.lineTotal.toStringAsFixed(0)}',
                      style: AdminText.numeric),
                ],
              ),
            ),
          if (request.reason != null && request.reason!.trim().isNotEmpty) ...[
            const SizedBox(height: AdminSpacing.sm),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(AdminSpacing.sm),
              decoration: BoxDecoration(
                color: AdminColors.neutralBg,
                borderRadius: AdminRadius.control,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('In their words', style: AdminText.overline),
                  const SizedBox(height: 2),
                  // Verbatim. Tidying a customer's reason removes the detail
                  // a shopkeeper decides on.
                  Text(request.reason!, style: AdminText.body),
                ],
              ),
            ),
          ],
          const SizedBox(height: AdminSpacing.md),
          if (_isDeciding)
            const Center(
              child: SizedBox(
                  height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2)),
            )
          else
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _reject,
                    style: OutlinedButton.styleFrom(
                      foregroundColor: AdminColors.danger,
                      side: const BorderSide(color: AdminColors.danger),
                    ),
                    child: const Text('Refuse'),
                  ),
                ),
                const SizedBox(width: AdminSpacing.md),
                Expanded(
                  child: FilledButton(
                    onPressed: _approve,
                    child: const Text('Approve'),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }
}
