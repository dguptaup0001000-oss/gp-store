import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../../core/api/api_client.dart';
import '../data/worker_repository.dart';
import '../domain/worker_models.dart';

/// Camera, one scan, one answer.
///
/// THE HARD PART IS NOT THE CAMERA, it is that a camera fires continuously. A
/// QR code held in front of a lens produces a detection every frame, and
/// without a guard the phone would send twenty identical scans in a second.
/// The [_submitting] latch and the per-scan request id below are what turn a
/// stream of detections into one physical scan.
///
/// WHAT THE WORKER IS TOLD is the server's own sentence, verbatim, whether it
/// is a yes or a no. "Order already assigned to Rahul" is worth ten times a
/// generic failure, because it tells them who to hand the carton to.
class WorkerScanScreen extends StatefulWidget {
  const WorkerScanScreen({super.key, required this.repository});

  final WorkerRepository repository;

  @override
  State<WorkerScanScreen> createState() => _WorkerScanScreenState();
}

class _WorkerScanScreenState extends State<WorkerScanScreen> {
  final MobileScannerController _controller = MobileScannerController(
    // One format, because we only ever print one. Narrowing it makes
    // detection faster and stops a stray barcode on the packaging - a
    // product's own EAN, say - from being read as an order label.
    formats: const [BarcodeFormat.qrCode],
    detectionSpeed: DetectionSpeed.noDuplicates,
  );

  final Random _random = Random.secure();

  bool _submitting = false;
  ScanOutcome? _result;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  /// A fresh id per physical scan, reused by every retry of that scan.
  ///
  /// This is the whole idempotency scheme from the app's side: the server uses
  /// it to tell "the worker scanned again" apart from "the same scan arrived
  /// twice", which is the difference between one record and two.
  String _newRequestId() {
    const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
    final suffix =
        List.generate(16, (_) => alphabet[_random.nextInt(alphabet.length)])
            .join();
    return '${DateTime.now().millisecondsSinceEpoch}-$suffix';
  }

  Future<void> _onDetect(BarcodeCapture capture) async {
    if (_submitting || _result != null) return;

    final raw = capture.barcodes.map((b) => b.rawValue).firstWhere(
        (v) => v != null && v.trim().isNotEmpty,
        orElse: () => null);
    if (raw == null) return;

    setState(() => _submitting = true);
    // Stop the camera the instant we have something. Leaving it running would
    // keep firing detections at a screen that is already committing one.
    await _controller.stop();
    HapticFeedback.mediumImpact();

    ScanOutcome outcome;
    try {
      outcome = await widget.repository.packScan(
        qrToken: raw.trim(),
        clientRequestId: _newRequestId(),
      );
    } on ApiException catch (e) {
      outcome =
          ScanOutcome(accepted: false, outcome: 'ERROR', message: e.message);
    } catch (_) {
      outcome = const ScanOutcome(
        accepted: false,
        outcome: 'ERROR',
        message: 'Could not complete the scan. Try again.',
      );
    }

    if (!mounted) return;
    setState(() {
      _submitting = false;
      _result = outcome;
    });
  }

  Future<void> _scanAnother() async {
    setState(() => _result = null);
    await _controller.start();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Scan order QR')),
      body: SafeArea(
        child: _result == null ? _camera() : _outcomeView(_result!),
      ),
    );
  }

  Widget _camera() {
    return Stack(
      alignment: Alignment.center,
      children: [
        MobileScanner(controller: _controller, onDetect: _onDetect),

        // A plain square, not an animated laser. On a cheap phone every
        // painted frame competes with the decoder for the same CPU, and a
        // worker in a doorway needs the code to be read, not entertained.
        IgnorePointer(
          child: Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(
              border: Border.all(color: Colors.white70, width: 3),
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ),

        if (_submitting)
          Container(
            color: Colors.black54,
            child: const Center(child: CircularProgressIndicator()),
          ),

        Positioned(
          bottom: 28,
          left: 24,
          right: 24,
          child: Text(
            'Hold the label inside the square',
            textAlign: TextAlign.center,
            style: const TextStyle(color: Colors.white, fontSize: 16),
          ),
        ),
      ],
    );
  }

  Widget _outcomeView(ScanOutcome outcome) {
    final theme = Theme.of(context);

    // Three states, not two. A QUEUED scan is NOT a success - the order has
    // not been recorded against anybody - and showing it as one would have a
    // worker walk away from a carton nobody is accountable for.
    final Color colour;
    final IconData icon;
    if (outcome.accepted) {
      colour = theme.colorScheme.primary;
      icon = Icons.check_circle;
    } else if (outcome.queued) {
      colour = theme.colorScheme.tertiary;
      icon = Icons.cloud_off;
    } else {
      colour = theme.colorScheme.error;
      icon = Icons.cancel;
    }

    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Icon(icon, size: 96, color: colour),
          const SizedBox(height: 24),
          if (outcome.orderNumber != null) ...[
            Text(
              outcome.orderNumber!,
              textAlign: TextAlign.center,
              style: theme.textTheme.headlineLarge,
            ),
            const SizedBox(height: 12),
          ],
          Text(
            outcome.message,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyLarge?.copyWith(color: colour),
          ),
          if (outcome.replayed) ...[
            const SizedBox(height: 8),
            const Text(
              'This scan had already been recorded.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white60, fontSize: 13),
            ),
          ],
          const SizedBox(height: 40),

          // ON AN ACCEPTED SCAN, THE PACKING LIST IS THE NEXT THING. The order
          // already arrived with this response, so opening it costs no request
          // at all - which is what makes scan-then-pack feel like one action.
          // The worker screen pops and the home screen pushes the order, so
          // "back" from the order lands somewhere sensible rather than on a
          // dead camera.
          if (outcome.accepted && outcome.order != null) ...[
            SizedBox(
              height: 64,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(outcome),
                child: const Text('OPEN ORDER', style: TextStyle(fontSize: 18)),
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 56,
              child: OutlinedButton(
                onPressed: _scanAnother,
                child:
                    const Text('SCAN ANOTHER', style: TextStyle(fontSize: 17)),
              ),
            ),
          ] else ...[
            SizedBox(
              height: 64,
              child: FilledButton(
                onPressed: _scanAnother,
                child:
                    const Text('SCAN ANOTHER', style: TextStyle(fontSize: 18)),
              ),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 56,
              child: OutlinedButton(
                onPressed: () => Navigator.of(context).pop(outcome),
                child: const Text('DONE', style: TextStyle(fontSize: 17)),
              ),
            ),
          ],
        ],
      ),
    );
  }
}
