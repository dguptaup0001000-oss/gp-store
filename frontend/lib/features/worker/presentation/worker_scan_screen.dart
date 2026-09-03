import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../../core/api/error_messages.dart';
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
  bool _torchOn = false;
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
    //
    // Guarded because this is not the important part: a stop() that throws
    // (already stopped, camera yanked by the OS) must not abandon a scan the
    // worker has already made. The _submitting latch above is what actually
    // prevents duplicates; stopping is a battery and CPU courtesy.
    try {
      await _controller.stop();
    } catch (_) {}
    HapticFeedback.mediumImpact();

    ScanOutcome outcome;
    try {
      outcome = await widget.repository.packScan(
        qrToken: raw.trim(),
        clientRequestId: _newRequestId(),
      );
    } catch (e) {
      // THE SERVER'S SENTENCE, WHICH IS THE WHOLE POINT OF THIS SCREEN.
      //
      // This was `on ApiException` with a generic fallback, and the first
      // clause could never match - ApiClient throws a DioException CARRYING
      // an ApiException, not the ApiException itself. So every refusal showed
      // "Could not complete the scan. Try again.", and the one thing a worker
      // holding a carton needs - "Order already assigned to Rahul" - was
      // discarded by the screen whose documented purpose is to show it.
      outcome = ScanOutcome(
        accepted: false,
        outcome: 'ERROR',
        message: extractErrorMessage(e),
      );
    }

    if (!mounted) return;
    setState(() {
      _submitting = false;
      _result = outcome;
    });
  }

  /// The way in when the camera will not oblige.
  ///
  /// WHY THIS EXISTS. A cracked lens, a filthy one, a dark storeroom, or a
  /// phone that simply refuses to focus - and until now the app's answer was
  /// "ask an administrator to record the order for you", which is a worker
  /// standing at a bench unable to do their job.
  ///
  /// WHAT IS TYPED IS NOT THE ORDER NUMBER. It is the short random code
  /// printed beside the QR on the same label, and the difference matters:
  /// order numbers are sequential and printed on the customer's invoice, so
  /// accepting one would let any worker claim an order they never held. The
  /// server refuses order numbers for exactly that reason.
  Future<void> _typeCode() async {
    final controller = TextEditingController();
    String? code;
    try {
      code = await _askForCode(controller);
    } finally {
      // A controller that outlives its dialog is a leak, and this screen can
      // be opened and dismissed many times in a shift at a packing bench.
      controller.dispose();
    }

    final typed = code?.trim() ?? '';
    if (typed.isEmpty || !mounted) return;

    setState(() => _submitting = true);
    // Same as a scan from here on. The camera is stopped because a successful
    // claim leaves this screen showing an outcome, not a live preview.
    try {
      await _controller.stop();
    } catch (_) {}

    ScanOutcome outcome;
    try {
      outcome = await widget.repository.packScan(
        qrToken: typed,
        clientRequestId: _newRequestId(),
      );
    } catch (e) {
      outcome = ScanOutcome(
        accepted: false,
        outcome: 'ERROR',
        message: extractErrorMessage(e),
      );
    }

    if (!mounted) return;
    setState(() {
      _submitting = false;
      _result = outcome;
    });
  }

  Future<String?> _askForCode(TextEditingController controller) {
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Type the code on the label'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'The 8-character code printed next to the QR square. '
              'Dashes and spaces do not matter.',
            ),
            const SizedBox(height: 16),
            TextField(
              controller: controller,
              autofocus: true,
              // characters, not the number pad: the code is letters AND
              // digits, and a worker sent to the wrong keyboard types the
              // wrong thing and then blames the label.
              textCapitalization: TextCapitalization.characters,
              autocorrect: false,
              enableSuggestions: false,
              style: const TextStyle(
                  fontSize: 24, letterSpacing: 4, fontFamily: 'monospace'),
              decoration: const InputDecoration(
                hintText: 'K7M4P2QX',
                border: OutlineInputBorder(),
              ),
              onSubmitted: (value) => Navigator.of(context).pop(value),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('CANCEL'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text),
            child: const Text('CLAIM ORDER'),
          ),
        ],
      ),
    );
  }

  Future<void> _toggleTorch() async {
    try {
      await _controller.toggleTorch();
      if (!mounted) return;
      setState(() => _torchOn = !_torchOn);
    } catch (_) {
      // Some devices have no torch, and a few refuse it while the camera is
      // warming up. Neither is worth an error in front of somebody trying to
      // scan - the button simply does nothing and the state stays honest.
    }
  }

  Future<void> _scanAnother() async {
    setState(() => _result = null);
    try {
      await _controller.start();
    } catch (_) {
      // Restarting a camera that is already running throws on some devices.
      // The preview is live either way, which is all this call was for.
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Scan order QR'),
        actions: [
          // IN THE BAR, NOT BURIED. A worker whose camera is failing is
          // already having a bad minute; making them discover this behind a
          // menu would be the same dead end with extra steps.
          if (_result == null)
            TextButton.icon(
              onPressed: _submitting ? null : _typeCode,
              icon: const Icon(Icons.keyboard),
              label: const Text('TYPE CODE'),
            ),
        ],
      ),
      body: SafeArea(
        child: _result == null ? _camera() : _outcomeView(_result!),
      ),
    );
  }

  Widget _camera() {
    return Stack(
      alignment: Alignment.center,
      children: [
        MobileScanner(
          controller: _controller,
          onDetect: _onDetect,
          // A DENIED CAMERA LOOKS EXACTLY LIKE A BROKEN APP without this.
          // The default is an empty black rectangle, so a worker who tapped
          // "Deny" once is left staring at a dead screen with nothing telling
          // them what happened or where to fix it.
          // Three parameters, not two: mobile_scanner 5.2.3's
          // MobileScannerErrorBuilder is (BuildContext, MobileScannerException,
          // Widget?). The child is the placeholder we have no use for.
          errorBuilder: (context, error, child) =>
              _CameraProblem(error: error, onTypeCode: _typeCode),
        ),

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
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Hold the label inside the square',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white, fontSize: 16),
              ),
              const SizedBox(height: 16),
              // DELIVERIES HAPPEN AFTER DARK. A printed label in an unlit
              // stairwell is the normal case for the evening run, not an edge
              // one, and without this the worker's only option is to carry the
              // carton back out to the street.
              FilledButton.tonalIcon(
                onPressed: _toggleTorch,
                icon: Icon(_torchOn ? Icons.flashlight_off : Icons.flashlight_on),
                label: Text(_torchOn ? 'LIGHT OFF' : 'LIGHT ON'),
              ),
            ],
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


/// Shown in place of the camera preview when it cannot run.
///
/// Names the fault and where to fix it. "Permission denied" is actionable
/// only if the worker is told it is a phone setting rather than a broken app.
/// What a worker sees when the camera will not start.
///
/// IT NOW OFFERS A WAY FORWARD. Every branch here used to end in "ask an
/// administrator", which meant a worker with a broken lens could not claim an
/// order at all - the exact dead end that made typing the code necessary.
class _CameraProblem extends StatelessWidget {
  const _CameraProblem({required this.error, required this.onTypeCode});

  final MobileScannerException error;
  final VoidCallback onTypeCode;

  String get _explanation {
    switch (error.errorCode) {
      case MobileScannerErrorCode.permissionDenied:
        return 'Camera permission was refused. Enable Camera for this app in '
            'Settings > Apps > GP-Store Worker, then come back and scan.';
      case MobileScannerErrorCode.unsupported:
        return 'This phone cannot open its camera for scanning. Type the code '
            'printed next to the QR square instead.';
      default:
        return 'The camera could not be started. Type the code printed next to '
            'the QR square, or close the app fully and open it again.';
    }
  }

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      color: Colors.black,
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.no_photography_outlined,
                  size: 72, color: Colors.white70),
              const SizedBox(height: 20),
              Text(
                _explanation,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, fontSize: 16),
              ),
              const SizedBox(height: 24),
              // Big, because this is now the only way this worker gets the
              // order onto their phone.
              SizedBox(
                height: 56,
                child: FilledButton.icon(
                  onPressed: onTypeCode,
                  icon: const Icon(Icons.keyboard),
                  label: const Text('TYPE THE CODE INSTEAD',
                      style: TextStyle(fontSize: 16)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
