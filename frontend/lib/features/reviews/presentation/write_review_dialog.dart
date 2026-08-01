import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../auth/presentation/auth_providers.dart' show extractErrorMessage;
import 'reviews_providers.dart';

class WriteReviewDialog extends ConsumerStatefulWidget {
  const WriteReviewDialog({super.key, required this.productId});

  final int productId;

  @override
  ConsumerState<WriteReviewDialog> createState() => _WriteReviewDialogState();
}

class _WriteReviewDialogState extends ConsumerState<WriteReviewDialog> {
  int _rating = 5;
  final _commentController = TextEditingController();
  bool _isSaving = false;
  String? _errorMessage;

  @override
  void dispose() {
    _commentController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _isSaving = true;
      _errorMessage = null;
    });

    try {
      await ref.read(reviewsRepositoryProvider).submitReview(
            productId: widget.productId,
            rating: _rating,
            comment: _commentController.text.trim().isEmpty ? null : _commentController.text.trim(),
          );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      // Most likely failure here is the backend's verified-purchase check -
      // surface that real message rather than a generic one, since it's
      // actionable ("you need to have bought this first").
      if (mounted) setState(() => _errorMessage = extractErrorMessage(e));
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Write a Review'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(5, (index) {
              final starValue = index + 1;
              return IconButton(
                icon: Icon(
                  starValue <= _rating ? Icons.star : Icons.star_border,
                  color: AppColors.primary,
                ),
                tooltip: 'Rate $starValue star${starValue == 1 ? '' : 's'}',
                onPressed: () => setState(() => _rating = starValue),
              );
            }),
          ),
          TextField(
            controller: _commentController,
            maxLines: 3,
            decoration: const InputDecoration(hintText: 'Share your experience (optional)'),
          ),
          if (_errorMessage != null) ...[
            const SizedBox(height: 8),
            Text(_errorMessage!, style: const TextStyle(color: AppColors.error, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          onPressed: _isSaving ? null : _submit,
          child: _isSaving
              ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Submit'),
        ),
      ],
    );
  }
}
