import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Local, zero-network artwork for catalogue entries that do not have a real
/// merchant/category photo yet.
///
/// WHY THIS IS LOCAL. Synthetic marketplace data and freshly-onboarded shops
/// often have no uploaded images. Pulling "random" internet pictures makes the
/// app slower, creates licensing/availability risk, and can misrepresent a
/// merchant's product. This widget instead renders a lightweight illustration
/// from the product/category name. A genuine image URL still has first priority
/// everywhere: GpNetworkImage draws this only while a real image is loading or
/// when there is no usable image.
///
/// The artwork is deliberately deterministic. The same product keeps the same
/// visual across rebuilds/restarts, while [variant] lets cards with similar
/// names use slightly different compositions/backgrounds in a large synthetic
/// demo so the feed does not look cloned.
enum LocalArtworkRole {
  product,
  category,
  visitToBuy,
  serviceAtShop,
}

class LocalCatalogueArtwork extends StatelessWidget {
  const LocalCatalogueArtwork({
    super.key,
    required this.seed,
    this.role = LocalArtworkRole.product,
    this.variant = 0,
    this.borderRadius = const BorderRadius.all(Radius.circular(12)),
  });

  final String seed;
  final LocalArtworkRole role;
  final int variant;
  final BorderRadius borderRadius;

  static int _stableHash(String value) {
    var hash = 2166136261;
    for (final unit in value.codeUnits) {
      hash ^= unit;
      hash = (hash * 16777619) & 0x7fffffff;
    }
    return hash;
  }

  _ArtworkSpec _spec() {
    final text = seed.toLowerCase();

    if (_has(text, const ['salt', 'namak', 'sendha', 'pink salt', 'rock salt'])) {
      return const _ArtworkSpec('🧂', Icons.restaurant_rounded, 'Salt & spices');
    }
    if (_has(text, const ['phone', 'mobile', 'iphone', 'smartphone', 'handset'])) {
      return const _ArtworkSpec('📱', Icons.smartphone_rounded, 'Phones');
    }
    if (_has(text, const ['laptop', 'computer', 'desktop', 'tablet'])) {
      return const _ArtworkSpec('💻', Icons.laptop_rounded, 'Computers');
    }
    if (_has(text, const ['usb', 'cable', 'charger', 'adapter', 'earphone', 'headphone'])) {
      return const _ArtworkSpec('🔌', Icons.cable_rounded, 'Electronics');
    }
    if (_has(text, const ['soap', 'shampoo', 'beauty', 'personal care', 'cosmetic'])) {
      return const _ArtworkSpec('🧼', Icons.spa_rounded, 'Personal care');
    }
    if (_has(text, const ['shoe', 'footwear', 'sandal', 'slipper'])) {
      return const _ArtworkSpec('👟', Icons.directions_walk_rounded, 'Footwear');
    }
    if (_has(text, const ['hardware', 'tool', 'drill', 'hammer', 'nut bolt'])) {
      return const _ArtworkSpec('🧰', Icons.handyman_rounded, 'Hardware');
    }
    if (_has(text, const ['repair', 'mechanic', 'service', 'wash', 'salon', 'barber'])) {
      return const _ArtworkSpec('🛠️', Icons.build_circle_outlined, 'Local service');
    }
    if (_has(text, const ['milk', 'dairy', 'curd', 'paneer', 'egg'])) {
      return const _ArtworkSpec('🥛', Icons.local_drink_rounded, 'Dairy');
    }
    if (_has(text, const ['banana'])) {
      return const _ArtworkSpec('🍌', Icons.eco_rounded, 'Fruit');
    }
    if (_has(text, const ['apple', 'fruit', 'mango', 'orange', 'grape'])) {
      return const _ArtworkSpec('🍎', Icons.eco_rounded, 'Fruit');
    }
    if (_has(text, const ['vegetable', 'tomato', 'potato', 'onion', 'carrot'])) {
      return const _ArtworkSpec('🥦', Icons.eco_rounded, 'Vegetables');
    }
    if (_has(text, const ['namkeen', 'snack', 'chips', 'biscuit', 'bakery'])) {
      return const _ArtworkSpec('🍿', Icons.lunch_dining_rounded, 'Snacks');
    }
    if (_has(text, const ['tea', 'coffee', 'beverage', 'drink', 'juice'])) {
      return const _ArtworkSpec('☕', Icons.local_cafe_rounded, 'Beverages');
    }
    if (_has(text, const ['atta', 'rice', 'dal', 'flour', 'grain', 'cereal'])) {
      return const _ArtworkSpec('🌾', Icons.grass_rounded, 'Staples');
    }
    if (_has(text, const ['oil', 'ghee'])) {
      return const _ArtworkSpec('🫙', Icons.local_dining_rounded, 'Oils & ghee');
    }
    if (_has(text, const ['cloth', 'fashion', 'shirt', 'saree', 'dress', 'garment'])) {
      return const _ArtworkSpec('👕', Icons.checkroom_rounded, 'Fashion');
    }
    if (_has(text, const ['jewel', 'gold', 'ring', 'necklace'])) {
      return const _ArtworkSpec('💍', Icons.diamond_outlined, 'Jewellery');
    }
    if (_has(text, const ['medicine', 'pharmacy', 'tablet', 'capsule'])) {
      return const _ArtworkSpec('💊', Icons.medical_services_outlined, 'Medicine');
    }
    if (_has(text, const ['baby', 'diaper'])) {
      return const _ArtworkSpec('🍼', Icons.child_care_rounded, 'Baby care');
    }
    if (_has(text, const ['clean', 'household', 'detergent', 'floor cleaner'])) {
      return const _ArtworkSpec('🧹', Icons.cleaning_services_rounded, 'Home care');
    }
    if (_has(text, const ['sweet', 'chocolate', 'confection'])) {
      return const _ArtworkSpec('🍫', Icons.cake_outlined, 'Sweets');
    }
    if (_has(text, const ['restaurant', 'food', 'pizza', 'biryani', 'meal'])) {
      return const _ArtworkSpec('🍲', Icons.restaurant_rounded, 'Food');
    }

    return switch (role) {
      LocalArtworkRole.serviceAtShop =>
        const _ArtworkSpec('🛠️', Icons.build_circle_outlined, 'Service'),
      LocalArtworkRole.visitToBuy =>
        const _ArtworkSpec('🛍️', Icons.storefront_rounded, 'Visit to buy'),
      LocalArtworkRole.category =>
        const _ArtworkSpec('🛒', Icons.category_rounded, 'Category'),
      LocalArtworkRole.product =>
        const _ArtworkSpec('🛍️', Icons.shopping_bag_rounded, 'Product'),
    };
  }

  static bool _has(String text, List<String> needles) =>
      needles.any(text.contains);

  @override
  Widget build(BuildContext context) {
    final spec = _spec();
    final hash = _stableHash('$seed|$variant|${role.name}');
    final palette = _palettes[hash % _palettes.length];
    final rotation = ((hash % 9) - 4) * .018;

    return RepaintBoundary(
      child: ClipRRect(
        borderRadius: borderRadius,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final w = constraints.maxWidth.isFinite ? constraints.maxWidth : 120.0;
            final h = constraints.maxHeight.isFinite ? constraints.maxHeight : w;
            final small = w < 82 || h < 82;
            final emojiSize = (w * (small ? .43 : .37)).clamp(24.0, 72.0);
            final iconSize = (w * (small ? .20 : .16)).clamp(13.0, 30.0);

            return DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: palette,
                ),
              ),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Positioned(
                    right: -w * .10,
                    top: -h * .12,
                    child: _Bubble(
                      size: w * .52,
                      color: Colors.white.withValues(alpha: .28),
                    ),
                  ),
                  Positioned(
                    left: -w * .14,
                    bottom: -h * .18,
                    child: _Bubble(
                      size: w * .58,
                      color: AppColors.primary.withValues(alpha: .08),
                    ),
                  ),
                  Align(
                    alignment: const Alignment(0, -.04),
                    child: Transform.rotate(
                      angle: rotation,
                      child: Container(
                        width: (w * .58).clamp(42.0, 150.0),
                        height: (h * .58).clamp(42.0, 150.0),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: .82),
                          borderRadius: BorderRadius.circular(
                              (w * .16).clamp(12.0, 30.0)),
                          border: Border.all(
                            color: Colors.white.withValues(alpha: .92),
                          ),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withValues(alpha: .08),
                              blurRadius: small ? 5 : 12,
                              offset: Offset(0, small ? 2 : 5),
                            ),
                          ],
                        ),
                        alignment: Alignment.center,
                        child: Text(
                          spec.emoji,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: emojiSize,
                            height: 1,
                          ),
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    right: small ? 5 : 10,
                    bottom: small ? 5 : 10,
                    child: Container(
                      width: small ? 24 : 34,
                      height: small ? 24 : 34,
                      decoration: BoxDecoration(
                        color: AppColors.primary.withValues(alpha: .90),
                        shape: BoxShape.circle,
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: .10),
                            blurRadius: 6,
                            offset: const Offset(0, 2),
                          ),
                        ],
                      ),
                      child: Icon(
                        _roleIcon(spec.icon),
                        color: Colors.white,
                        size: iconSize,
                      ),
                    ),
                  ),
                  if (!small)
                    Positioned(
                      left: 10,
                      bottom: 10,
                      child: Container(
                        constraints: BoxConstraints(maxWidth: w * .58),
                        padding: const EdgeInsets.symmetric(
                            horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: .84),
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(
                          spec.label,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: AppColors.textPrimary,
                            fontSize: 9.5,
                            height: 1,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  IconData _roleIcon(IconData semantic) => switch (role) {
        LocalArtworkRole.serviceAtShop => Icons.build_circle_outlined,
        LocalArtworkRole.visitToBuy => Icons.storefront_rounded,
        LocalArtworkRole.category => semantic,
        LocalArtworkRole.product => semantic,
      };

  static const _palettes = <List<Color>>[
    [Color(0xFFFFF1B8), Color(0xFFFFD778)],
    [Color(0xFFDDF4E7), Color(0xFFA8DFC1)],
    [Color(0xFFDDEEFF), Color(0xFFAED3F7)],
    [Color(0xFFFFE0E9), Color(0xFFFFBBCD)],
    [Color(0xFFEDE4FF), Color(0xFFCDBAF8)],
    [Color(0xFFFFE8CC), Color(0xFFF7C78D)],
    [Color(0xFFE2F5F4), Color(0xFFAEDFD9)],
    [Color(0xFFF2E8D5), Color(0xFFDCC79F)],
  ];
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.size, required this.color});

  final double size;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        width: size,
        height: size,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      );
}

class _ArtworkSpec {
  const _ArtworkSpec(this.emoji, this.icon, this.label);

  final String emoji;
  final IconData icon;
  final String label;
}
