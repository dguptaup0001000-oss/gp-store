import 'package:flutter/material.dart';

/// Opens a product detail screen with a subtle scale-and-fade.
///
/// Flutter's default Android route slides the new page in from the right,
/// which is fine for navigation but says nothing about the object being
/// opened. This grows the detail page from 96% while fading it in, so
/// tapping a product feels like the product itself expanding rather than a
/// different screen arriving.
///
/// KEPT DELIBERATELY SMALL. 0.96 to 1.0 over 220ms - enough to register,
/// short enough that a customer tapping through a grid never waits on it.
/// A hero flight of the product image was the obvious alternative and was
/// not used: it needs matching tags across every screen that shows a card,
/// breaks when the same product appears in two sections at once (the tag
/// collides), and janks if the image has not finished decoding. The
/// mis-fire cost is high and the visual gain over this is small.
///
/// Transform and opacity only - no filters, no saveLayer beyond what a route
/// transition already does.
Route<T> productPageRoute<T>(Widget page) {
  return PageRouteBuilder<T>(
    transitionDuration: const Duration(milliseconds: 220),
    reverseTransitionDuration: const Duration(milliseconds: 180),
    pageBuilder: (context, animation, secondaryAnimation) => page,
    transitionsBuilder: (context, animation, secondaryAnimation, child) {
      final curved = CurvedAnimation(
        parent: animation,
        // easeOutCubic decelerates into place: the page arrives and settles
        // rather than snapping, which is what makes a small scale read as
        // weight instead of a glitch.
        curve: Curves.easeOutCubic,
        reverseCurve: Curves.easeInCubic,
      );

      return FadeTransition(
        opacity: curved,
        child: ScaleTransition(
          scale: Tween<double>(begin: 0.96, end: 1.0).animate(curved),
          child: child,
        ),
      );
    },
  );
}
