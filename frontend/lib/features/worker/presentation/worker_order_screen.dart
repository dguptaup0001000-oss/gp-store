import 'package:flutter/material.dart';

import '../../../core/api/error_messages.dart';
import '../data/worker_repository.dart';
import '../domain/worker_models.dart';

/// One order: what to pack, where it goes, and what to press next.
///
/// THE SCREEN A WORKER ACTUALLY STANDS IN FRONT OF, so it is a list and two
/// numbers and nothing else. No images, no animation, no cards inside cards.
/// A phone held in one hand at arm's length in a shop doorway in daylight.
///
/// THE BUTTONS ARE THE SERVER'S. Every action offered here comes from
/// [WorkerOrder.allowedNext], which the server computed from the delivery's
/// real current status. This screen contains no rule about which status may
/// follow which - so it cannot offer a move the server would refuse, and a
/// phone running an old build cannot invent one that no longer exists.
class WorkerOrderScreen extends StatefulWidget {
  const WorkerOrderScreen({
    super.key,
    required this.repository,
    required this.order,
  });

  final WorkerRepository repository;
  final WorkerOrder order;

  @override
  State<WorkerOrderScreen> createState() => _WorkerOrderScreenState();
}

class _WorkerOrderScreenState extends State<WorkerOrderScreen> {
  late WorkerOrder _order;

  /// The status currently being sent, or null.
  ///
  /// NOT A BOOLEAN, because it does two jobs: it disables every button while
  /// one request is in flight (the rapid-double-tap case, which on a status
  /// change is a request the server may refuse the second time), and it names
  /// which button to show a spinner on.
  String? _sending;

  String? _error;

  @override
  void initState() {
    super.initState();
    _order = widget.order;
  }

  /// Moves that end the delivery, and cannot be walked back from the phone.
  ///
  /// DELIVERED and CANCELLED are terminal: nothing in [WorkerOrder.allowedNext]
  /// leads out of them, so a mis-tap is a trip back to the shop and an
  /// administrator. Every button on this screen is 56 logical pixels tall and
  /// sits under a thumb holding a carton, which is the right size for the
  /// common case and exactly the wrong size for this one.
  static const _irreversible = {'DELIVERED', 'CANCELLED'};

  Future<bool> _confirm(String status) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Mark ${humanizeStatus(status).toLowerCase()}?'),
        content: Text(
          status == 'CANCELLED'
              ? 'This cancels the delivery of ${_order.orderNumber}. It cannot '
                  'be undone from this app.'
              : 'This records ${_order.orderNumber} as handed to the customer. '
                  'It cannot be undone from this app.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Go back'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(humanizeStatus(status)),
          ),
        ],
      ),
    );
    return confirmed ?? false;
  }

  Future<void> _move(String status) async {
    final deliveryId = _order.deliveryId;
    if (deliveryId == null || _sending != null) {
      return;
    }

    if (_irreversible.contains(status)) {
      if (!await _confirm(status)) return;
      if (!mounted) return;
    }

    setState(() {
      _sending = status;
      _error = null;
    });

    try {
      await widget.repository
          .setDeliveryStatus(deliveryId: deliveryId, status: status);

      // RE-READ RATHER THAN ASSUME. The screen could set the new status
      // locally and be right nearly always - but "nearly" is the problem: the
      // next allowed moves come from the server too, and guessing them here
      // would put this screen's idea of the rules back in the app, which is
      // the thing this design is avoiding.
      final refreshed = await widget.repository.order(_order.orderId);
      if (!mounted) return;
      setState(() => _order = refreshed);
    } catch (e) {
      if (!mounted) return;
      // THE SERVER'S OWN SENTENCE, read from the parsed error rather than
      // scraped out of it. This used to run a regex for `"message":"..."`
      // over error.toString(), which depended on Dio choosing to include the
      // response body in its string form - it does not reliably - and could
      // not survive an escaped quote or a nested "message" key. When it
      // missed, a refused transition became a connection message, sending a
      // worker to check a network that had just answered them.
      //
      // A refusal here says what IS allowed: "This delivery is PACKED, so it
      // cannot go straight to DELIVERED. Allowed next: PICKED_UP, CANCELLED."
      // That is the only part a worker can act on.
      setState(() => _error = extractErrorMessage(e));
    } finally {
      if (mounted) setState(() => _sending = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final collect = _order.amountToCollect;
    final showCash = _order.cashOnDelivery && collect != null && collect > 0;

    return Scaffold(
      appBar: AppBar(title: Text(_order.orderNumber)),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          _StatusLine(
            orderStatus: _order.orderStatus,
            deliveryStatus: _order.deliveryStatus,
          ),
          if (showCash) ...[
            const SizedBox(height: 16),
            // THE ONE PIECE OF MONEY ON THIS SCREEN, and it is not the order
            // total - it is what to take at the door. A prepaid order shows
            // nothing here at all, which is how a customer avoids being asked
            // to pay twice.
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.tertiaryContainer,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('COLLECT CASH',
                      style: theme.textTheme.labelLarge
                          ?.copyWith(letterSpacing: 1.2)),
                  const SizedBox(height: 4),
                  Text(formatRupees(collect),
                      style: theme.textTheme.headlineLarge),
                ],
              ),
            ),
          ],
          const SizedBox(height: 24),
          Text(
              '${_order.totalItems} item${_order.totalItems == 1 ? '' : 's'} to pack',
              style: theme.textTheme.titleLarge),
          const SizedBox(height: 8),
          for (final line in _order.items) _PackingLine(line: line),
          if (_order.deliveryAddress != null) ...[
            const SizedBox(height: 24),
            Text('Deliver to', style: theme.textTheme.titleLarge),
            const SizedBox(height: 4),
            if (_order.customerName != null) Text(_order.customerName!),
            Text(_order.deliveryAddress!),
            if (_order.customerPhone != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: SelectableText(_order.customerPhone!,
                    style: theme.textTheme.bodyLarge),
              ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 20),
            Text(_error!,
                style: theme.textTheme.bodyLarge
                    ?.copyWith(color: theme.colorScheme.error)),
          ],
          const SizedBox(height: 28),
          if (_order.deliveryId == null)
            Text(
              'No delivery has been assigned for this order yet, so there is '
              'nothing to update here. It is recorded as packed by you.',
              style: theme.textTheme.bodyMedium,
            )
          else
            for (final next in _order.allowedNext) ...[
              SizedBox(
                width: double.infinity,
                height: 56,
                child: FilledButton(
                  // Disabled while ANY request is in flight, not just this
                  // button's. Two different statuses sent back to back is the
                  // double-tap that actually causes trouble.
                  onPressed: _sending == null ? () => _move(next) : null,
                  child: _sending == next
                      ? const SizedBox(
                          height: 22,
                          width: 22,
                          child: CircularProgressIndicator(strokeWidth: 2))
                      : Text(humanizeStatus(next)),
                ),
              ),
              const SizedBox(height: 10),
            ],
        ],
      ),
    );
  }

}

class _StatusLine extends StatelessWidget {
  const _StatusLine({this.orderStatus, this.deliveryStatus});

  final String? orderStatus;
  final String? deliveryStatus;

  @override
  Widget build(BuildContext context) {
    // Humanised, like the buttons below it. This printed the raw constant
    // while _label() sat in the same file turning PICKED_UP into "Picked up"
    // for the buttons - so one screen showed a worker "OUT_FOR_DELIVERY" as
    // the current status and "Out for delivery" on the button, and left them
    // to work out that those are the same thing.
    final shown = deliveryStatus ?? orderStatus;
    return Row(
      children: [
        Text('Status: ', style: Theme.of(context).textTheme.bodyLarge),
        Text(shown == null ? 'Unknown' : humanizeStatus(shown),
            style: Theme.of(context).textTheme.titleLarge),
      ],
    );
  }
}

class _PackingLine extends StatelessWidget {
  const _PackingLine({required this.line});

  final WorkerOrderLine line;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // The count first and big. A worker reads down the left edge
          // counting things into a carton; the name is what they check second.
          SizedBox(
            width: 46,
            child: Text('${line.quantity}x', style: theme.textTheme.titleLarge),
          ),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.name, style: theme.textTheme.bodyLarge),
                if (line.pack != null)
                  Text(line.pack!,
                      style: theme.textTheme.bodyMedium
                          ?.copyWith(color: theme.colorScheme.outline)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
