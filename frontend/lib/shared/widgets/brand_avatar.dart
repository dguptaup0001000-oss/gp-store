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

  /// Deep, desaturated tones drawn around the GP-Store identity rather than
  /// the stock web-colour wheel this used before.
  ///
  /// A brand mark should read as a distinct object without shouting: the old
  /// set (electric blue, purple, tomato red) was brighter than anything else
  /// on the page, so a row of brand avatars pulled focus away from the
  /// products. These are all dark enough to carry white type and muted
  /// enough to sit inside the palette.
  static const _palette = [
    Color(0xFF14653F), // forest - the house colour
    Color(0xFF0F766E), // deep teal
    Color(0xFFB45309), // burnt amber
    Color(0xFF1E3A5F), // navy
    Color(0xFF7C2D3E), // deep rose
    Color(0xFF3F6212), // olive
    Color(0xFF4C1D95), // deep violet
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
