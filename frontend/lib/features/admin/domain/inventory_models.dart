import 'package:freezed_annotation/freezed_annotation.dart';

part 'inventory_models.freezed.dart';
part 'inventory_models.g.dart';

/// Mirrors backend's InventoryResponse exactly - real product name/brand
/// included, same reasoning as CartResponse/OrderDetailResponse (the raw
/// entity's variant->product link is hidden from JSON to prevent a
/// recursion bug, so a flat DTO fills the gap explicitly).
@freezed
class InventoryItem with _$InventoryItem {
  const factory InventoryItem({
    required int id,
    int? variantId,
    String? productName,
    String? productBrand,
    double? variantQuantity,
    String? unit,
    int? stock,
    int? reservedStock,
    int? minimumStock,
    int? maximumStock,
  }) = _InventoryItem;

  const InventoryItem._();

  factory InventoryItem.fromJson(Map<String, dynamic> json) => _$InventoryItemFromJson(json);

  /// Stock actually available to sell right now - reservedStock is already
  /// committed to orders in progress, not free inventory.
  int get availableStock => (stock ?? 0) - (reservedStock ?? 0);

  bool get isLowStock => minimumStock != null && availableStock <= minimumStock!;
}
