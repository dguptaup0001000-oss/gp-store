import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/inventory_models.dart';

void main() {
  group('InventoryItem.availableStock', () {
    test('subtracts reserved stock from raw stock - reserved is already committed to orders', () {
      const item = InventoryItem(id: 1, stock: 50, reservedStock: 12);
      expect(item.availableStock, 38);
    });

    test('treats null reservedStock as zero reserved', () {
      const item = InventoryItem(id: 1, stock: 50);
      expect(item.availableStock, 50);
    });

    test('treats null stock as zero, not a crash', () {
      const item = InventoryItem(id: 1, reservedStock: 5);
      expect(item.availableStock, -5);
    });
  });

  group('InventoryItem.isLowStock', () {
    test('is true when available stock is at or below the reorder point', () {
      const atThreshold = InventoryItem(id: 1, stock: 10, minimumStock: 10);
      const belowThreshold = InventoryItem(id: 1, stock: 5, minimumStock: 10);
      expect(atThreshold.isLowStock, isTrue);
      expect(belowThreshold.isLowStock, isTrue);
    });

    test('accounts for reserved stock, not just raw stock', () {
      // Raw stock looks fine (20), but 15 are already reserved for other
      // orders - only 5 truly available, which IS at the reorder point.
      const item = InventoryItem(id: 1, stock: 20, reservedStock: 15, minimumStock: 5);
      expect(item.isLowStock, isTrue);
    });

    test('is false when no minimumStock is configured - nothing to compare against', () {
      const item = InventoryItem(id: 1, stock: 1);
      expect(item.isLowStock, isFalse);
    });

    test('is false when comfortably above the reorder point', () {
      const item = InventoryItem(id: 1, stock: 100, minimumStock: 10);
      expect(item.isLowStock, isFalse);
    });
  });
}
