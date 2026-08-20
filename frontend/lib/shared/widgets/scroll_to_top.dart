import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';

/// Wraps a scrollable page and floats a "Back to top" pill over it once the
/// customer has scrolled a meaningful distance.
///
/// ONE COMPONENT, USED EVERYWHERE. Every long page in the app gets the same
/// button in the same place with the same behaviour, and a change to any of
/// that happens here rather than in six screens.
///
/// HOW IT AVOIDS REBUILDING THE PAGE. A scroll controller fires on every
/// frame of every scroll - putting setState in that listener would rebuild
/// the entire product grid sixty times a second, which is exactly the kind
/// of "small feature" that quietly costs a page its smoothness. Instead the
/// listener writes to a ValueNotifier<bool>, which no-ops when the value is
/// unchanged, and only the pill itself is subscribed to it. The page above
/// never rebuilds because of this widget - not on scroll, and not when the
/// button appears.
///
/// WHERE IT SITS. The pill is placed inside the page BODY, so on any screen
/// whose Scaffold has a bottomNavigationBar - the floating "View cart" bar,
/// the app's bottom nav, or both nested - the body is already laid out above
/// them and the button lands above them too, in the required order:
///
///     Back to top  ->  View cart  ->  bottom navigation
///
/// That falls out of the layout rather than being hand-tuned per screen, so
/// it stays correct when the cart bar appears and disappears.
class ScrollToTop extends StatefulWidget {
  const ScrollToTop({super.key, required this.builder});

  /// Builds the page's scrollable, given the controller it must attach to.
  ///
  /// A builder rather than magic: the controller has to reach the actual
  /// ScrollView, and passing it explicitly means there is no dependence on
  /// PrimaryScrollController inheritance rules, which vary by platform and
  /// by widget and would fail silently when they did not apply.
  final Widget Function(BuildContext context, ScrollController controller) builder;

  @override
  State<ScrollToTop> createState() => _ScrollToTopState();
}

class _ScrollToTopState extends State<ScrollToTop> {
  final _controller = ScrollController();

  /// Not setState: see the class doc. Only the pill listens to this.
  final _isVisible = ValueNotifier<bool>(false);

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onScroll);
  }

  @override
  void dispose() {
    _controller.removeListener(_onScroll);
    _controller.dispose();
    _isVisible.dispose();
    super.dispose();
  }

  void _onScroll() {
    // A controller with no attached scrollable has no offset to read. This
    // happens between the widget being built and the ScrollView attaching,
    // and again if the page swaps its scrollable out.
    if (!_controller.hasClients) {
      _isVisible.value = false;
      return;
    }

    final position = _controller.position;

    // Threshold measured in screens rather than pixels: a fixed 400px is
    // most of a small phone and a third of a tablet, so the button would
    // appear after a flick on one and only after real scrolling on the
    // other. "A bit more than one screenful" is the same amount of
    // scrolling everywhere.
    final showAt = position.viewportDimension * 1.2;

    // Hysteresis, so a scroll that comes to rest right on the threshold does
    // not flicker the button in and out as the list settles.
    final hideAt = showAt * 0.6;

    final offset = position.pixels;
    if (offset > showAt) {
      _isVisible.value = true;
    } else if (offset < hideAt) {
      _isVisible.value = false;
    }
  }

  Future<void> _backToTop() async {
    if (!_controller.hasClients) return;

    HapticFeedback.selectionClick();

    // animateTo, not jumpTo: this scrolls the page the customer is already
    // on. Nothing is rebuilt, refetched or reset - the feed keeps every page
    // it has loaded, filters stay as they were, and the cart is untouched.
    //
    // Duration is capped rather than proportional to distance: forty screens
    // down, a proportional animation would take several seconds and feel
    // broken. 400ms is long enough to read as travel rather than a jump.
    await _controller.animateTo(
      0,
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeOutCubic,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        widget.builder(context, _controller),
        Positioned(
          left: 0,
          right: 0,
          bottom: 16,
          child: ValueListenableBuilder<bool>(
            valueListenable: _isVisible,
            builder: (context, isVisible, child) {
              return _BackToTopPill(isVisible: isVisible, onPressed: _backToTop);
            },
          ),
        ),
      ],
    );
  }
}

/// The pill itself. Separated so the animation state lives below the
/// ValueListenableBuilder and a press does not rebuild anything above it.
class _BackToTopPill extends StatefulWidget {
  const _BackToTopPill({required this.isVisible, required this.onPressed});

  final bool isVisible;
  final VoidCallback onPressed;

  @override
  State<_BackToTopPill> createState() => _BackToTopPillState();
}

class _BackToTopPillState extends State<_BackToTopPill> {
  bool _pressed = false;

  void _setPressed(bool value) {
    if (_pressed == value) return;
    setState(() => _pressed = value);
  }

  @override
  Widget build(BuildContext context) {
    // IgnorePointer while hidden is a correctness rule, not a nicety: a
    // fully transparent button still takes hit tests, so without this an
    // invisible pill would swallow taps aimed at the ADD button on the
    // product card underneath it.
    return IgnorePointer(
      ignoring: !widget.isVisible,
      child: AnimatedOpacity(
        opacity: widget.isVisible ? 1 : 0,
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
        // AnimatedOpacity forces a saveLayer, which this codebase avoids in
        // scrolling grids - see AppElevation's doc comment. It is fine here:
        // one 40px pill, animating only when the button appears or leaves,
        // never per card and never per scroll frame.
        child: AnimatedScale(
          // Rises into place slightly rather than simply fading, so it reads
          // as arriving rather than as something that was always there.
          scale: widget.isVisible ? (_pressed ? 0.94 : 1.0) : 0.85,
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutBack,
          child: Center(
            child: DecoratedBox(
              // Deliberately a shadow rather than a border: the pill floats
              // over products, and depth is what says "this is above the
              // page" without adding another line to an already busy grid.
              // The same two-layer card shadow used everywhere else, so it
              // sits at the app's own height rather than inventing one.
              decoration: const BoxDecoration(
                borderRadius: BorderRadius.all(Radius.circular(999)),
                color: AppColors.cardBackground,
                boxShadow: AppElevation.card,
              ),
              child: Material(
                color: Colors.transparent,
                shape: const StadiumBorder(),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  customBorder: const StadiumBorder(),
                  onTapDown: (_) => _setPressed(true),
                  onTapUp: (_) => _setPressed(false),
                  // A finger that slides off must not leave the pill stuck
                  // in its pressed state.
                  onTapCancel: () => _setPressed(false),
                  onTap: widget.onPressed,
                  child: const Padding(
                    padding: EdgeInsets.fromLTRB(6, 6, 16, 6),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        // The arrow gets the brand green as a filled disc;
                        // the label stays dark on white. A fully green pill
                        // would compete with the "View cart" bar sitting
                        // directly below it, which is the one green pill on
                        // screen that should pull the eye.
                        CircleAvatar(
                          radius: 14,
                          backgroundColor: AppColors.primary,
                          child: Icon(Icons.arrow_upward_rounded, size: 17, color: Colors.white),
                        ),
                        SizedBox(width: 10),
                        Text(
                          'Back to top',
                          style: TextStyle(
                            fontSize: 13.5,
                            fontWeight: FontWeight.w700,
                            color: AppColors.textPrimary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
