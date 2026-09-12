import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/marketplace/marketplace_models.dart';
import 'package:gpstore/core/marketplace/marketplace_providers.dart';
import 'package:gpstore/core/marketplace/marketplace_repository.dart';
import 'package:gpstore/core/marketplace/shop_context.dart';
import 'package:gpstore/core/store/store_status.dart';
import 'package:gpstore/core/store/store_status_provider.dart';

import '../../support/test_api_client.dart';

/// A backend that answers nothing - an older deployment, or a route that has
/// been taken away.
class _RefusingMarketplace implements MarketplaceRepository {
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      Future<Never>.error(Exception('no such route'));
}

void main() {
  // ShopSwitch discards the cart along with the rest of the last shop's data,
  // and reaching the cart provider reaches TokenStorage's platform channel.
  setUpAll(setUpFakeSecureStorage);

  group('what the app decides it is', () {
    test('a backend that cannot answer is treated as a single shop', () async {
      // The REPOSITORY is replaced, not the provider, so the real provider's
      // own fallback is what runs. Overriding the provider would test the
      // override.
      final container = ProviderContainer(overrides: [
        marketplaceRepositoryProvider
            .overrideWithValue(_RefusingMarketplace()),
      ]);
      addTearDown(container.dispose);

      final mode = await container.read(marketplaceModeProvider.future);
      expect(mode.multiShop, isFalse,
          reason: 'BEING WRONG IN THIS DIRECTION COSTS A FEATURE. Being wrong '
              'the other way draws a shop switcher over a deployment that has '
              'one shop, on an app that was working.');
      expect(container.read(isMarketplaceProvider), isFalse);
    });

    test('marketplace UI stays off until the backend says otherwise', () {
      // Loading is not "yes". A switcher that appears because an answer has
      // not arrived is a switcher on a single-shop app.
      final container = ProviderContainer(
        overrides: [
          marketplaceModeProvider.overrideWith(
              (ref) => Future<MarketplaceMode>.delayed(
                  const Duration(seconds: 5), () => MarketplaceMode.singleShop)),
        ],
      );
      addTearDown(container.dispose);

      expect(container.read(isMarketplaceProvider), isFalse);
    });

    test('marketplace UI turns on only for a multi-shop answer', () async {
      final container = ProviderContainer(
        overrides: [
          marketplaceModeProvider.overrideWith((ref) async =>
              const MarketplaceMode(mode: 'MULTI_SHOP_PRODUCTION', multiShop: true)),
        ],
      );
      addTearDown(container.dispose);

      await container.read(marketplaceModeProvider.future);
      expect(container.read(isMarketplaceProvider), isTrue);
    });
  });

  group('switching shop', () {
    /// The store banner polls, and a real Dio request under the test binding
    /// leaves a pending timer - the same reason home_load_stage_test overrides
    /// it. A single-value stream has nothing outstanding.
    ProviderContainer switching() => ProviderContainer(overrides: [
          storeStatusProvider
              .overrideWith((ref) => Stream.value(StoreStatus.unknown())),
        ]);

    test('selecting a shop puts it on the request, and re-selecting it is a '
        'no-op', () {
      final container = switching();
      addTearDown(container.dispose);

      expect(container.read(shopContextProvider), isNull,
          reason: 'nothing chosen is the state a single-shop app is always in');

      container.read(shopSwitchProvider).select(7);
      expect(container.read(shopContextProvider), 7);

      // Selecting the shop already selected must not throw away the current
      // shop's catalogue - that is a full reload for no change.
      container.read(shopSwitchProvider).select(7);
      expect(container.read(shopContextProvider), 7);
    });

    test('clearing hands the choice back to the backend', () {
      final container = switching();
      addTearDown(container.dispose);

      container.read(shopSwitchProvider).select(7);
      container.read(shopSwitchProvider).clear();

      expect(container.read(shopContextProvider), isNull,
          reason: 'null means "you choose" - the nearest serving shop');
    });
  });
}
