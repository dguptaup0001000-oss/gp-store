import 'package:flutter/material.dart';

/// Generic operation feedback that is safe for every GP-STORE application.
///
/// Customer-only navigation actions belong in action_feedback.dart so the
/// Merchant and Super Admin import graphs never acquire checkout dependencies.
void showActionFailure(BuildContext context, String message) {
  final messenger = ScaffoldMessenger.of(context);
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(
    SnackBar(content: Text(message), duration: const Duration(seconds: 4)),
  );
}

void showActionSuccess(BuildContext context, String message) {
  final messenger = ScaffoldMessenger.of(context);
  messenger.hideCurrentSnackBar();
  messenger.showSnackBar(SnackBar(
    duration: const Duration(seconds: 2),
    content: Row(children: [
      const Icon(Icons.check_circle_outline, color: Colors.white, size: 20),
      const SizedBox(width: 8),
      Expanded(child: Text(message)),
    ]),
  ));
}
