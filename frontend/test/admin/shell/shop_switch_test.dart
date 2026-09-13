import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/admin/shell/shop_switch.dart';

import 'dart:io';

/// Switching shop must take the last shop's data with it.
///
/// THE RISK THIS GUARDS. The invalidation list in shop_switch.dart is written
/// by hand, because ApiClient deliberately reads the selected shop per request
/// rather than rebuilding - so there is no cascade to ride on. A hand-written
/// list rots: somebody adds a provider next month, forgets this file, and a
/// merchant sees Deepak Hardware's stock under Deepak Saree. That is a data
/// leak between two businesses that happen to share an owner, and the merchant
/// has no way to tell it happened.
///
/// So this test reads the provider files and fails when a shop-scoped provider
/// is not accounted for. It turns a thing people forget into a thing the build
/// refuses.
void main() {
  /// Providers whose answers are NOT about one shop, and so must not be in the
  /// list. Each is here with the reason it is exempt.
  const notShopScoped = <String>{
    // Repositories and plumbing: stateless, hold no answers.
    'shopSelfServiceRepositoryProvider',
    'adminProductsRepositoryProvider',
    'adminWorkersRepositoryProvider',
    'catalogImportRepositoryProvider',
    'deliveryPricingRepositoryProvider',
    'territoryRepositoryProvider',
    'storeOperationsRepositoryProvider',
    // UI state the merchant chose, which survives a switch on purpose: the
    // period they are looking at, and the date they are packing for, are about
    // the person and not the shop.
    'analyticsPeriodDaysProvider',
    'preparationDateProvider',
  };

  test('every shop-scoped provider is cleared when the shop changes', () {
    final declared = File('lib/admin/shell/shop_switch.dart').readAsStringSync();

    final sources = <String>[
      'lib/features/admin/presentation/admin_providers.dart',
      'lib/features/admin/presentation/shop_self_service_providers.dart',
      'lib/admin/operations/store_operations_providers.dart',
    ];

    final missing = <String>[];
    for (final path in sources) {
      final text = File(path).readAsStringSync();
      for (final match
          in RegExp(r'^final\s+(\w+Provider)\b', multiLine: true).allMatches(text)) {
        final name = match.group(1)!;
        if (notShopScoped.contains(name)) {
          continue;
        }
        if (!declared.contains(name)) {
          missing.add('$name  (declared in $path)');
        }
      }
    }

    expect(
      missing,
      isEmpty,
      reason: 'These providers hold one shop\'s answers and are not cleared when the '
          'merchant switches shop, so the next shop would show the last one\'s data:\n'
          '  ${missing.join('\n  ')}\n'
          'Add them to shopScopedProviders in lib/admin/shell/shop_switch.dart, or - '
          'if the answer genuinely is not about a shop - to notShopScoped in this '
          'test with the reason why.',
    );
  });

  test('the list is not empty, which is the way this test could pass for nothing', () {
    // A regex that stopped matching, or a file that moved, would make the loop
    // above iterate over nothing and report success forever.
    expect(shopScopedProviders.length, greaterThan(20));
  });
}
