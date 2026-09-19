import 'package:flutter/foundation.dart';

/// A department belonging to THIS shop.
///
/// NOT THE PLATFORM TAXONOMY. `Category` (in products/domain) is the shared
/// tree every merchant picks from, where renaming a row renames it for
/// everybody - which is why only the platform may write it, and why a phone
/// shop tapping Add Category was told "You don't have permission to do that".
///
/// This is the other thing the merchant actually wanted: somewhere of their
/// own to put chargers. Creating one adds a row this shop owns; no other
/// merchant sees it, and no customer's category tree grows an entry.
@immutable
class ShopCategory {
  const ShopCategory({
    required this.id,
    required this.name,
    this.description,
    this.globalCategoryId,
    this.displayOrder,
    this.active = true,
  });

  final int id;
  final String name;
  final String? description;

  /// The platform department this one stands for, when it stands for one.
  /// Null means "purely this shop's own".
  final int? globalCategoryId;

  final int? displayOrder;
  final bool active;

  factory ShopCategory.fromJson(Map<String, dynamic> json) => ShopCategory(
        id: (json['id'] as num).toInt(),
        name: (json['name'] ?? '').toString(),
        description: json['description'] as String?,
        globalCategoryId: (json['globalCategoryId'] as num?)?.toInt(),
        displayOrder: (json['displayOrder'] as num?)?.toInt(),
        active: json['active'] as bool? ?? true,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'description': description,
        'globalCategoryId': globalCategoryId,
        'displayOrder': displayOrder,
        'active': active,
      };

  @override
  bool operator ==(Object other) => other is ShopCategory && other.id == id;

  @override
  int get hashCode => id.hashCode;
}
