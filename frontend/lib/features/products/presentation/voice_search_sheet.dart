import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../../../core/theme/app_theme.dart';
import '../../../core/util/app_haptics.dart';
import '../../../core/voice/speech_service.dart';
import '../../../core/voice/voice_query_parser.dart';

/// The listening sheet.
///
/// Opened from the microphone, closed with a [VoiceQuery] or with null. It
/// owns the microphone for exactly as long as it is on screen and hands back
/// a parsed result - it never searches, navigates or touches the cart, so the
/// search screen keeps owning all of that.
///
/// WHY A SHEET RATHER THAN AN INLINE STATE. Listening is a mode: the keyboard
/// must go away, the customer needs to know the microphone is open, and there
/// has to be one obvious way out. A sheet gives all three for free, including
/// dismissal by swiping - and dismissal has to release the microphone, which
/// is what dispose() below is for.
class VoiceSearchSheet extends StatefulWidget {
  const VoiceSearchSheet({super.key, this.service});

  /// Injectable so the sheet can be widget-tested without a microphone.
  final SpeechService? service;

  /// Shows the sheet and resolves to what was understood, or null if the
  /// customer backed out.
  static Future<VoiceQuery?> show(BuildContext context, {SpeechService? service}) {
    return showModalBottomSheet<VoiceQuery>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => VoiceSearchSheet(service: service),
    );
  }

  @override
  State<VoiceSearchSheet> createState() => _VoiceSearchSheetState();
}

enum _Stage { listening, understanding, problem }

class _VoiceSearchSheetState extends State<VoiceSearchSheet> {
  late final SpeechService _speech = widget.service ?? SpeechService();

  _Stage _stage = _Stage.listening;
  String _heard = '';
  VoiceOutcome? _problem;

  @override
  void initState() {
    super.initState();
    // After the first frame, so the sheet is visibly open before the
    // permission dialog can appear over it. Asking from initState puts the
    // system prompt on screen before the customer can see what asked for it.
    WidgetsBinding.instance.addPostFrameCallback((_) => _listen());
  }

  @override
  void dispose() {
    // Swiping the sheet away must close the microphone. Without this it keeps
    // listening to a customer who thinks they have cancelled.
    _speech.cancel();
    super.dispose();
  }

  Future<void> _listen() async {
    setState(() {
      _stage = _Stage.listening;
      _heard = '';
      _problem = null;
    });

    final result = await _speech.listenOnce(
      onPartial: (words) {
        if (!mounted) return;
        setState(() => _heard = words);
      },
    );

    if (!mounted) return;

    if (result.outcome != VoiceOutcome.heard || !result.hasText) {
      AppHaptics.action();
      setState(() {
        _stage = _Stage.problem;
        _problem = result.outcome;
      });
      return;
    }

    setState(() {
      _stage = _Stage.understanding;
      _heard = result.transcript;
    });

    final query = VoiceQueryParser.parse(result.transcript);

    // Heard perfectly well, understood nothing usable - somebody clearing
    // their throat, or a sentence of pure politeness. Treated as a problem
    // with a transcript rather than as a search for "", which would show an
    // empty results page as though the product did not exist.
    if (query.isEmpty) {
      AppHaptics.action();
      setState(() {
        _stage = _Stage.problem;
        _problem = VoiceOutcome.noSpeech;
      });
      return;
    }

    AppHaptics.selection();
    if (mounted) Navigator.of(context).pop(query);
  }

  Future<void> _stopAndUse() async {
    AppHaptics.selection();
    await _speech.stop();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.fromLTRB(24, 20, 24, 28),
        decoration: const BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: switch (_stage) {
            _Stage.listening => _listening(),
            _Stage.understanding => _understanding(),
            _Stage.problem => _problemView(),
          },
        ),
      ),
    );
  }

  List<Widget> _listening() => [
        const _PulsingMic(),
        const SizedBox(height: 16),
        const Text(
          'Listening...',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
        ),
        const SizedBox(height: 6),
        Text(
          _heard.isEmpty ? 'Bolo - jaise "do kilo chini"' : _heard,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 14,
            height: 1.35,
            color: _heard.isEmpty ? AppColors.textSecondary : AppColors.textPrimary,
          ),
        ),
        const SizedBox(height: 20),
        // Not everybody pauses long enough for the recogniser to decide they
        // have finished, and a customer who has said their piece should not
        // have to wait three seconds to find out the app agrees.
        FilledButton(onPressed: _stopAndUse, child: const Text('Done')),
      ];

  List<Widget> _understanding() => [
        const SizedBox(
          height: 40,
          width: 40,
          child: CircularProgressIndicator(strokeWidth: 3),
        ),
        const SizedBox(height: 16),
        const Text(
          'Understanding your request...',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
        ),
        const SizedBox(height: 6),
        Text(
          '"$_heard"',
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 14, color: AppColors.textSecondary),
        ),
      ];

  List<Widget> _problemView() {
    final problem = _problem;
    final retryable = VoiceResult(problem ?? VoiceOutcome.failed).isRetryable;
    final needsSettings = problem == VoiceOutcome.permissionPermanentlyDenied;

    return [
      Icon(_problemIcon(problem), size: 40, color: AppColors.textSecondary),
      const SizedBox(height: 14),
      Text(
        _problemTitle(problem),
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.textPrimary),
      ),
      const SizedBox(height: 6),
      Text(
        _problemDetail(problem),
        textAlign: TextAlign.center,
        style: const TextStyle(fontSize: 13, height: 1.35, color: AppColors.textSecondary),
      ),
      const SizedBox(height: 18),
      Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          // Typing always remains available. A voice feature that traps
          // somebody when it fails is worse than no voice feature.
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Type instead'),
          ),
          const SizedBox(width: 8),
          // Only ONE of these, and only when it can actually work. A retry
          // that cannot succeed - no recogniser, permission refused for good -
          // teaches people the button is a lie.
          if (needsSettings)
            FilledButton(
              onPressed: () {
                AppHaptics.selection();
                openAppSettings();
              },
              child: const Text('Open settings'),
            )
          else if (retryable)
            FilledButton(onPressed: _listen, child: const Text('Try again')),
        ],
      ),
    ];
  }

  IconData _problemIcon(VoiceOutcome? problem) => switch (problem) {
        VoiceOutcome.permissionDenied ||
        VoiceOutcome.permissionPermanentlyDenied =>
          Icons.mic_off_outlined,
        VoiceOutcome.recognizerUnavailable => Icons.mic_none_outlined,
        VoiceOutcome.networkUnavailable => Icons.wifi_off_rounded,
        VoiceOutcome.busy => Icons.phonelink_erase_outlined,
        _ => Icons.hearing_disabled_outlined,
      };

  String _problemTitle(VoiceOutcome? problem) => switch (problem) {
        VoiceOutcome.permissionDenied => 'Microphone permission needed',
        VoiceOutcome.permissionPermanentlyDenied => 'Microphone is blocked',
        VoiceOutcome.recognizerUnavailable => 'Voice search needs Google speech services',
        VoiceOutcome.networkUnavailable => 'No internet for voice',
        VoiceOutcome.busy => 'The microphone is in use',
        VoiceOutcome.noSpeech => "I didn't catch that",
        _ => "Voice search isn't available right now",
      };

  /// Every one of these names a DIFFERENT thing the customer can do. A single
  /// message for all of them - which is what this screen used to show - tells
  /// somebody whose microphone is simply switched off to go and look for a
  /// fault that is not there.
  String _problemDetail(VoiceOutcome? problem) => switch (problem) {
        VoiceOutcome.permissionDenied =>
          'GP-Store needs the microphone to hear your search. Tap the mic again to allow it, '
              'or type instead.',
        VoiceOutcome.permissionPermanentlyDenied =>
          'Microphone access is turned off for GP-Store. Turn it on in Settings to search by '
              'voice - typing works either way.',
        VoiceOutcome.recognizerUnavailable =>
          "This phone has no speech recogniser switched on. It usually comes from the Google "
              'app - check that it is installed and enabled. Typing works as usual.',
        VoiceOutcome.networkUnavailable =>
          'Speech recognition needs a connection. Your products are still searchable by typing.',
        VoiceOutcome.busy =>
          'Another app is using the microphone. Close it and try again.',
        VoiceOutcome.noSpeech =>
          'Try again a little closer to the phone - for example, "aashirvaad atta paanch kilo".',
        _ => 'Something went wrong listening. You can try again or type your search.',
      };
}

/// The microphone, breathing.
///
/// A still icon and a listening icon look identical, and "is it actually
/// hearing me" is the first thing anybody wonders. The pulse is the cheapest
/// honest answer - it says the app is awake without pretending to visualise a
/// waveform it does not measure.
class _PulsingMic extends StatefulWidget {
  const _PulsingMic();

  @override
  State<_PulsingMic> createState() => _PulsingMicState();
}

class _PulsingMicState extends State<_PulsingMic> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Respects the system setting: somebody who has asked for less motion
    // gets a steady microphone rather than none at all.
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    return AnimatedBuilder(
      animation: _controller,
      builder: (context, child) {
        final scale = reduceMotion ? 1.0 : 1.0 + (_controller.value * 0.18);
        return Container(
          height: 76,
          width: 76,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: AppColors.tint(AppColors.primary),
          ),
          child: Transform.scale(
            scale: scale,
            child: const Icon(Icons.mic, size: 34, color: AppColors.primary),
          ),
        );
      },
    );
  }
}
