import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

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

  /// Opens the rider's maps app on the order's confirmed coordinates.
  ///
  /// THE DESTINATION IS THE PIN, NOT THE TEXT. "House 42, Gupta Nagar" is for
  /// a human to read at a door; a maps app given that string guesses, and in a
  /// colony where the numbering restarts it guesses wrong. These two numbers
  /// are what the customer dragged onto their doorstep, snapshotted when the
  /// order was placed.
  ///
  /// A universal maps URL rather than the Android-only `geo:` scheme, matching
  /// what delivery_dashboard_screen.dart already does - it opens the installed
  /// app where there is one and the website where there is not, which is the
  /// fallback rather than a separate code path.
  Future<void> _navigate() async {
    final lat = _order.latitude;
    final lng = _order.longitude;
    if (lat == null || lng == null) return;

    final uri = Uri.parse(
        'https://www.google.com/maps/dir/?api=1&destination=$lat,$lng');
    var opened = false;
    try {
      opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      opened = false;
    }
    if (opened || !mounted) return;

    // Nothing on this phone could take the URL. Show the coordinates rather
    // than a dead end - a rider can read them into another device, and it is
    // the only thing left that still gets the carton to the right house.
    setState(() => _error =
        'No maps app could be opened. Delivery location: $lat, $lng');
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

          // UNDER THEIR OWN HEADINGS, not glued onto the address line. These
          // are the two lines that actually find the door once the rider is on
          // the right street, and a run-on string read one-handed loses them.
          if ((_order.landmark ?? '').isNotEmpty) ...[
            const SizedBox(height: 16),
            Text('Landmark', style: theme.textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(_order.landmark!, style: theme.textTheme.bodyLarge),
          ],

          if ((_order.deliveryInstructions ?? '').isNotEmpty) ...[
            const SizedBox(height: 16),
            Text('Instructions', style: theme.textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(_order.deliveryInstructions!, style: theme.textTheme.bodyLarge),
          ],

          if (_order.hasDestination) ...[
            const SizedBox(height: 20),
            SizedBox(
              height: 56,
              child: OutlinedButton.icon(
                onPressed: _navigate,
                icon: const Icon(Icons.navigation),
                label: const Text('NAVIGATE', style: TextStyle(fontSize: 17)),
              ),
            ),
          ] else ...[
            const SizedBox(height: 20),
            // Said out loud rather than shown as a dead button. An order whose
            // address predates map confirmation has no pin, and a rider needs
            // to know that before they set off, not after.
            Text(
              'No confirmed map location for this order. Use the address and '
              'landmark above.',
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.outline),
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

          // THE PACKET, NOT ITS NAME. Walking a shelf, a worker recognises a
          // pouch far faster than they read "Aachi Chilli Powder 500 g" - and
          // two masalas from one brand sit side by side looking nothing alike.
          //
          // A FIXED BOX WHETHER OR NOT THERE IS A PHOTO, so the names stay on
          // one vertical line down the list. A list that jogs left and right
          // as images appear is harder to read than one with no images at all.
          _PackingPhoto(url: line.imageUrl),
          const SizedBox(width: 12),

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

/// The product photo on a packing line, and what stands in when there is none.
///
/// EVERY FAILURE ENDS IN THE SAME GREY BOX. A signed image URL can expire
/// mid-shift, the storeroom's signal can drop, the variant may simply have no
/// picture - and none of those are worth showing a worker a broken-image icon
/// or a spinner that never resolves. The box keeps its place in the row and
/// the name beside it still says what to pick.
class _PackingPhoto extends StatelessWidget {
  const _PackingPhoto({required this.url});

  final String? url;

  static const double _size = 52;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final placeholder = Container(
      width: _size,
      height: _size,
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(Icons.inventory_2_outlined,
          size: 24, color: theme.colorScheme.outline),
    );

    final source = url;
    if (source == null || source.isEmpty) {
      return placeholder;
    }

    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Image.network(
        source,
        width: _size,
        height: _size,
        fit: BoxFit.cover,
        // No spinner. A packing list that fills with turning circles on a
        // storeroom connection reads worse than one that fills in quietly.
        loadingBuilder: (context, child, progress) =>
            progress == null ? child : placeholder,
        errorBuilder: (context, error, stack) => placeholder,
      ),
    );
  }
}
