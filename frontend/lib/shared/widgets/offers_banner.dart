import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/theme/app_theme.dart';
import '../../features/products/domain/product_models.dart';
import '../../core/util/haptic_widgets.dart';

/// Auto-rotating offers carousel. Advances every 4 seconds and loops back
/// to the first offer after the last. Doesn't pause on user drag -- the
/// occasional interrupted auto-advance is an acceptable minor UX cost here
/// rather than added complexity for a low-stakes banner.
class OffersBanner extends StatefulWidget {
  const OffersBanner({super.key, required this.offers});

  final List<Coupon> offers;

  @override
  State<OffersBanner> createState() => _OffersBannerState();
}

class _OffersBannerState extends State<OffersBanner> {
  late final PageController _pageController;
  Timer? _timer;
  int _currentPage = 0;

  @override
  void initState() {
    super.initState();
    _pageController = PageController();
    _startAutoRotate();
  }

  void _startAutoRotate() {
    if (widget.offers.length <= 1) return;
    _timer = Timer.periodic(const Duration(seconds: 4), (_) {
      if (!_pageController.hasClients) return;
      final nextPage = (_currentPage + 1) % widget.offers.length;
      _pageController.animateToPage(
        nextPage,
        duration: const Duration(milliseconds: 400),
        curve: Curves.easeInOut,
      );
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _pageController.dispose();
    super.dispose();
  }

  Future<void> _copyCode(BuildContext context, Coupon offer) async {
    await Clipboard.setData(ClipboardData(text: offer.couponCode));

    if (!context.mounted) return;

    final details = <String>[];
    if (offer.minimumOrderAmount != null) {
      details.add('Min. order ₹${offer.minimumOrderAmount!.toStringAsFixed(0)}');
    }
    if (offer.maxDiscountAmount != null) {
      details.add('Up to ₹${offer.maxDiscountAmount!.toStringAsFixed(0)} off');
    }

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          'Code "${offer.couponCode}" copied'
          '${details.isNotEmpty ? ' - ${details.join(', ')}' : ''}',
        ),
        duration: const Duration(seconds: 4),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (widget.offers.isEmpty) return const SizedBox.shrink();

    return Column(
      children: [
        SizedBox(
          height: 76,
          child: PageView.builder(
            controller: _pageController,
            itemCount: widget.offers.length,
            onPageChanged: (index) => setState(() => _currentPage = index),
            itemBuilder: (context, index) {
              final offer = widget.offers[index];
              final label = offer.discountType.offerLabel(offer.discountValue);

              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: InkWell(
                  borderRadius: BorderRadius.circular(12),
                  onTap: hapticize(() => _copyCode(context, offer)),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    decoration: BoxDecoration(
                      color: AppColors.cardBackground,
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Row(
                      children: [
                        const Icon(Icons.local_offer_outlined, color: AppColors.primary),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(label, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
                              const SizedBox(height: 2),
                              Text(
                                'Tap to copy: ${offer.couponCode}',
                                style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontSize: 11),
                                overflow: TextOverflow.ellipsis,
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            },
          ),
        ),
        if (widget.offers.length > 1) ...[
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(
              widget.offers.length,
              (index) => Container(
                margin: const EdgeInsets.symmetric(horizontal: 3),
                width: _currentPage == index ? 16 : 6,
                height: 6,
                decoration: BoxDecoration(
                  color: _currentPage == index ? AppColors.primary : AppColors.cardBackground,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
            ),
          ),
        ],
      ],
    );
  }
}
