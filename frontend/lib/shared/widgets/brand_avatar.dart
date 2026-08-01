import 'package:flutter/material.dart';

/// There's no Brand entity with a logo in the backend - "brand" is just a
/// plain text field on Product. Rather than fake a logo image, this shows a
/// colored initial, the same honest pattern Gmail/Slack use when there's no
/// real photo. The color is deterministic (hashed from the name), so the
/// same brand always gets the same color across the app.
class BrandAvatar extends StatelessWidget {
  const BrandAvatar({super.key, required this.brandName, this.size = 56});

  final String brandName;
  final double size;

  static const _palette = [
    Color(0xFF1DB954),
    Color(0xFFE67E22),
    Color(0xFF3498DB),
    Color(0xFF9B59B6),
    Color(0xFFE74C3C),
    Color(0xFF16A085),
    Color(0xFFF39C12),
  ];

  @override
  Widget build(BuildContext context) {
    final initial = brandName.isNotEmpty ? brandName[0].toUpperCase() : '?';
    final color = _palette[brandName.hashCode.abs() % _palette.length];

    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      alignment: Alignment.center,
      child: Text(
        initial,
        style: TextStyle(color: Colors.white, fontSize: size * 0.4, fontWeight: FontWeight.w700),
      ),
    );
  }
}
