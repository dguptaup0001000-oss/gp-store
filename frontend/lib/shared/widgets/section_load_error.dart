import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../core/util/haptic_widgets.dart';

/// Compact error + retry for a home-screen section that failed to load.
///
/// Replaces a blank [SizedBox.shrink] so a network failure is visible
/// without turning the whole shop into an error page.
class SectionLoadError extends StatelessWidget {
  const SectionLoadError({
    super.key,
    required this.message,
    required this.onRetry,
  });

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      child: Row(
        children: [
          const Icon(Icons.error_outline, size: 18, color: AppColors.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style:
                  const TextStyle(color: AppColors.textSecondary, fontSize: 13),
            ),
          ),
          TextButton(
            onPressed: hapticize(onRetry),
            child: const Text('Retry'),
          ),
        ],
      ),
    );
  }
}
