import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('PlatformRepository', () {
    test('lists merchants with the status that decides whether they trade', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/merchants', (_) => const FakeResponse([
            {'id': 1, 'legalName': 'GP Retail', 'displayName': 'GP Store', 'status': 'ACTIVE'},
            {'id': 2, 'displayName': 'Sharma Kirana', 'status': 'APPROVED'},
          ]));

      final merchants =
          await PlatformRepository(apiClient: buildTestApiClient(adapter)).merchants();

      expect(merchants, hasLength(2));
      expect(merchants.first.isTrading, isTrue);
      expect(merchants.last.isTrading, isFalse,
          reason: 'APPROVED means the papers are in order; ACTIVE means the '
              'business is trading, and a shop under an approved-but-not-active '
              'merchant sells nothing');
    });

    test('a status change carries the reason it was made for', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('PUT', '/api/platform/merchants/2/status', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({'id': 2, 'status': 'APPROVED'});
      });

      await PlatformRepository(apiClient: buildTestApiClient(adapter)).setMerchantStatus(
        merchantId: 2,
        status: 'APPROVED',
        reason: 'papers checked',
      );

      expect(sent!['status'], 'APPROVED');
      expect(sent!['reason'], 'papers checked',
          reason: 'a status change nobody recorded a reason for is one nobody '
              'can account for later');
    });

    test('registering a merchant sends the owner account, and never a status',
        () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/merchants', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse(
            {'id': 9, 'legalName': 'Sharma Kirana', 'status': 'APPLICATION'});
      });

      final merchant =
          await PlatformRepository(apiClient: buildTestApiClient(adapter))
              .registerMerchant(
        legalName: 'Sharma Kirana',
        displayName: 'Sharma Store',
        contactPhone: '9876500000',
        ownerCustomerId: 42,
      );

      expect(sent!['legalName'], 'Sharma Kirana');
      expect(sent!['ownerCustomerId'], 42,
          reason: 'the owner account is what the server grants a staff row to '
              'on every shop opened under this merchant - without it the shop '
              'has nobody who can sign in');
      expect(sent!.containsKey('status'), isFalse,
          reason: 'A MERCHANT CANNOT BE CREATED ALREADY APPROVED. The status '
              'is the platform review sequence and belongs to the server; a '
              'field here would be a way to skip it');
      expect(sent!['demo'], false);
      expect(sent!.containsKey('contactEmail'), isFalse,
          reason: 'an empty field is sent as absent, not as an empty string - '
              'the server turns blank into null and a literal "" would defeat '
              'that');
      expect(merchant.status, 'APPLICATION');
      expect(merchant.isTrading, isFalse);
    });

    test('opening a shop names its merchant and cannot name its status', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/shops', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({
          'id': 7,
          'merchantId': 9,
          'code': 'SHARMA-1',
          'displayName': 'Sharma Store',
          'status': 'DRAFT',
        });
      });

      final shop = await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .openShop(
        merchantId: 9,
        code: 'SHARMA-1',
        displayName: 'Sharma Store',
        latitude: 27.16231,
        longitude: 83.940468,
        maxDeliveryRadiusKm: 5,
        timeZone: 'Asia/Kolkata',
      );

      expect(sent!['merchantId'], 9);
      expect(sent!['code'], 'SHARMA-1');
      expect(sent!['latitude'], 27.16231);
      expect(sent!['maxDeliveryRadiusKm'], 5);
      expect(sent!['timeZone'], 'Asia/Kolkata');
      expect(sent!.containsKey('status'), isFalse,
          reason: 'A SHOP CANNOT BE OPENED ALREADY SELLING. It arrives as a '
              'DRAFT and the platform moves it, so there is no status to send');
      expect(shop.status, 'DRAFT');
    });

    test('a shop opened with no coordinates sends none rather than zeroes',
        () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/shops', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({'id': 8, 'code': 'NOWHERE', 'status': 'DRAFT'});
      });

      await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .openShop(merchantId: 9, code: 'NOWHERE');

      // ZERO IS A PLACE. Sending 0,0 for "not set" puts the shop in the Gulf
      // of Guinea and makes it reachable by a discovery search that should
      // have skipped it entirely.
      expect(sent!.containsKey('latitude'), isFalse);
      expect(sent!.containsKey('longitude'), isFalse);
      expect(sent!.containsKey('maxDeliveryRadiusKm'), isFalse);
    });

    test('adding staff asks for the home shop explicitly', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/shops/7/staff', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({});
      });

      await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .addStaff(shopId: 7, customerId: 42);

      expect(sent!['customerId'], 42);
      expect(sent!['asDefault'], isTrue,
          reason: 'asDefault is an instruction, not a hint: without it an '
              'account that already has a home shop keeps it, so a merchant '
              'opening their second storefront is added and lands nowhere new');
    });

    test('opening a staff login returns the only copy of the password',
        () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/staff', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({
          'customerId': 55,
          'email': 'ravi@sharmakirana.test',
          'role': 'ADMIN',
          'oneTimePassword': 'k7Rmq3xTbYw9Zc',
        });
      });

      final opened = await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .openStaffAccount(
        fullName: 'Ravi Sharma',
        email: 'ravi@sharmakirana.test',
        mobileNumber: '9876543210',
        role: 'ADMIN',
      );

      expect(sent!['email'], 'ravi@sharmakirana.test');
      expect(sent!['role'], 'ADMIN');
      expect(sent!['mobileNumber'], '9876543210');
      // THE HOLE THIS FILLS. Until this route existed no API could make an
      // account an ADMIN - the only role ever assigned in code was
      // DELIVERY_BOY - so onboarding a merchant meant SQL on the box.
      expect(opened.customerId, 55,
          reason: "registerMerchant needs this id for ownerCustomerId, which "
              'is the whole reason the login is opened first');
      expect(opened.oneTimePassword, 'k7Rmq3xTbYw9Zc');
    });

    test('a blank phone is left out rather than sent empty', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/staff', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({'customerId': 56, 'oneTimePassword': 'x'});
      });

      await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .openStaffAccount(
              fullName: 'Ravi Sharma',
              email: 'ravi@x.test',
              mobileNumber: '',
              role: 'ADMIN');

      // An empty string is not the same as "not given": the form always has
      // a phone controller, and sending "" would store a blank number that
      // OTP delivery would later try to use.
      expect(sent!.containsKey('mobileNumber'), isFalse);
    });

    test('a reset issues a NEW password and never reads the old one', () async {
      final adapter = FakeHttpClientAdapter();
      var called = 0;
      adapter.on('POST', '/api/platform/staff/55/reset-password', (_) {
        called++;
        return const FakeResponse({
          'customerId': 55,
          'email': 'ravi@sharmakirana.test',
          'role': 'ADMIN',
          'oneTimePassword': 'w4Ptz8kMhQr2Ds',
        });
      });

      final reset = await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .resetStaffPassword(customerId: 55);

      expect(called, 1);
      // NOT A WAY TO READ THE CURRENT ONE. There is no such route,
      // deliberately: a merchant who cannot get in needs a new password, not
      // the platform owner reading their existing one.
      expect(reset.oneTimePassword, 'w4Ptz8kMhQr2Ds');
      expect(reset.customerId, 55);
    });

    test('the market overview is one line per shop, never a pooled total', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/overview', (_) => const FakeResponse({
            'periodDays': 30,
            'shops': [
              {'shopId': 1, 'orderCount': 10, 'grossSales': 1000.0, 'netSales': 950.0},
              {'shopId': 2, 'orderCount': 2, 'grossSales': 200.0, 'netSales': 200.0},
            ],
            'totals': {'orderCount': 12, 'grossSales': 1200.0, 'netSales': 1150.0,
                       'tradingShops': 2},
            'shopCount': 3,
            'merchantCount': 3,
          }));

      final overview =
          await PlatformRepository(apiClient: buildTestApiClient(adapter)).overview();

      expect(overview.shops, hasLength(2));
      expect(overview.shops.first.shopId, 1);
      expect(overview.totals!.tradingShops, 2);
      expect(overview.shopCount, 3,
          reason: 'three shops exist and two traded - a roll-up that only '
              'counted the ones with orders would hide a shop taking none');
    });
  });
}
