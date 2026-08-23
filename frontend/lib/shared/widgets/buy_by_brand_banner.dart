import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';
import '../../features/brands/presentation/brands_screen.dart';
import '../../features/products/domain/brand_models.dart';
import '../../features/products/presentation/brand_products_screen.dart';
import 'brand_avatar.dart';
import '../../core/util/haptic_widgets.dart';

/// "Shop by Brand" row for the home screen. Auto-advances one tile at a time
/// so brands rotate through without the user scrolling, but stays fully
/// swipeable - the timer pauses while a finger is down so a manual scroll
/// isn't yanked away mid-gesture.
class BuyByBrandBanner extends StatefulWidget {
  const BuyByBrandBanner({super.key, required this.brands});

  final List<BrandSummary> brands;

  @override
  State<BuyByBrandBanner> createState() => _BuyByBrandBannerState();
}

class _BuyByBrandBannerState extends State<BuyByBrandBanner> {
  /// How long each brand sits in place before the row slides on. Tune here.
  static const _slideInterval = Duration(milliseconds: 2500);
  static const _slideDuration = Duration(milliseconds: 450);

  late final PageController _controller;
  Timer? _timer;
  int _page = 0;

  @override
  void initState() {
    super.initState();
    _controller = PageController(viewportFraction: 0.28);
    _startTimer();
  }

  void _startTimer() {
    _timer?.cancel();
    if (widget.brands.length < 2) return;
    _timer = Timer.periodic(_slideInterval, (_) {
      if (!mounted || !_controller.hasClients) return;
      _page++;
      _controller.animateToPage(
        _page,
        duration: _slideDuration,
        curve: Curves.easeInOut,
      );
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _openBrand(BrandSummary brand) {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => BrandProductsScreen(brand: brand)),
    );
  }

  void _openAllBrands() {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const BrandsScreen()),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (widget.brands.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text('Shop by Brand',
                  style: Theme.of(context).textTheme.titleMedium),
              GestureDetector(
                onTap: hapticize(_openAllBrands),
                child: const Text(
                  'See All',
                  style: TextStyle(
                      color: AppColors.primary, fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
        ),
        SizedBox(
          height: 108,
          child: Listener(
            onPointerDown: (_) => _timer?.cancel(),
            onPointerUp: (_) {
              _page = _controller.page?.round() ?? _page;
              _startTimer();
            },
            child: PageView.builder(
              controller: _controller,
              padEnds: false,
              itemBuilder: (context, index) {
                final brand = widget.brands[index % widget.brands.length];
                return _BrandTile(brand: brand, onTap: hapticize(() => _openBrand(brand)));
              },
            ),
          ),
        ),
      ],
    );
  }
}

class _BrandTile extends StatelessWidget {
  const _BrandTile({required this.brand, required this.onTap});

  final BrandSummary brand;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            BrandAvatar(brandName: brand.brand, size: 56),
            const SizedBox(height: 6),
            Text(
              brand.brand,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w600),
            ),
            Text(
              '${brand.productCount} ${brand.productCount == 1 ? "item" : "items"}',
              style: const TextStyle(
                  fontSize: 11, color: AppColors.textSecondary),
            ),
          ],
        ),
      ),
    );
  }
}
