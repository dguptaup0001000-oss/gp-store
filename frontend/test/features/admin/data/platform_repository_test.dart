import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/data/platform_repository.dart';

import '../../../support/test_api_client.dart';

void main() {
  setUpAll(setUpFakeSecureStorage);

  group('PlatformRepository', () {
    test('global search is server-side, paged, and parses entity types', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/control/search', (options) {
        expect(options.queryParameters['q'], 'deepak');
        expect(options.queryParameters['page'], 2);
        expect(options.queryParameters['size'], 20);
        return const FakeResponse({
          'content': [
            {
              'entityType': 'CUSTOMER',
              'entityId': 42,
              'title': 'Deepak Kumar',
              'reference': 'C-42',
              'maskedEmail': 'd***@example.test'
            }
          ],
          'page': 2,
          'size': 20,
          'totalElements': 61,
          'totalPages': 4
        });
      });

      final page = await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .globalSearch(query: 'deepak', page: 2);

      expect(page.content.single.entityType, 'CUSTOMER');
      expect(page.content.single.entityId, 42);
      expect(page.hasMore, isTrue);
    });

    test('control tower sends the selected range and keeps finance meanings separate',
        () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/control/dashboard', (options) {
        expect(options.queryParameters['from'], '2026-09-01');
        expect(options.queryParameters['to'], '2026-09-07');
        return const FakeResponse({
          'from': '2026-09-01T00:00:00',
          'to': '2026-09-08T00:00:00',
          'marketplace': {'totalMerchants': 3},
          'orderStatuses': {'DELIVERED': 5},
          'finance': {
            'gmv': 125420.00,
            'merchantProductSales': 117000.00,
            'deliveryCharges': 8420.00,
            'platformCommission': 2000.00,
            'platformRevenueAvailable': false
          },
          'presenceAvailable': false
        });
      });

      final summary = await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .controlTowerDashboard(
        from: DateTime(2026, 9, 1),
        to: DateTime(2026, 9, 7),
      );

      expect(summary.count('totalMerchants'), 3);
      expect(summary.orders('DELIVERED'), 5);
      expect(summary.money('gmv'), isNot(summary.money('platformCommission')),
          reason: 'GMV and GP-STORE commission are different money facts');
    });

    test('resource filters stay server-side and preserve paging', () async {
      final adapter = FakeHttpClientAdapter();
      adapter.on('GET', '/api/platform/control/orders', (options) {
        expect(options.queryParameters, containsPair('q', 'GP-42'));
        expect(options.queryParameters, containsPair('page', 3));
        expect(options.queryParameters, containsPair('size', 25));
        expect(options.queryParameters, containsPair('status', 'DELIVERED'));
        expect(options.queryParameters, containsPair('merchantId', 7));
        expect(options.queryParameters, containsPair('shopId', 9));
        expect(options.queryParameters, containsPair('customerId', 11));
        expect(options.queryParameters, containsPair('workerId', 13));
        expect(options.queryParameters, containsPair('paymentStatus', 'SUCCESS'));
        expect(options.queryParameters, containsPair('paymentMethod', 'ONLINE'));
        expect(options.queryParameters, containsPair('from', '2026-09-01'));
        expect(options.queryParameters, containsPair('to', '2026-09-07'));
        return const FakeResponse({
          'content': [],
          'page': 3,
          'size': 25,
          'totalElements': 0,
          'totalPages': 0,
        });
      });

      final page = await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .controlTowerResource(
        resource: 'orders',
        query: 'GP-42',
        page: 3,
        status: 'DELIVERED',
        merchantId: 7,
        shopId: 9,
        customerId: 11,
        workerId: 13,
        paymentStatus: 'SUCCESS',
        paymentMethod: 'ONLINE',
        from: DateTime(2026, 9, 1),
        to: DateTime(2026, 9, 7),
      );

      expect(page.content, isEmpty);
      expect(page.page, 3);
    });

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

    test('onboarding sends one request for what used to take five', () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      var calls = 0;
      adapter.on('POST', '/api/platform/onboard', (options) {
        calls++;
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({
          'merchantId': 9,
          'businessName': 'Sharma Kirana',
          'shopId': 7,
          'shopCode': 'SHARMA-KIRANA',
          'ownerCustomerId': 55,
          'ownerEmail': 'ravi@sharmakirana.test',
          'oneTimePassword': 'k7Rmq3xTbYw9Zc',
        });
      });

      final opened =
          await PlatformRepository(apiClient: buildTestApiClient(adapter))
              .onboardMerchant(
        businessName: 'Sharma Kirana',
        ownerName: 'Ravi Sharma',
        ownerEmail: 'ravi@sharmakirana.test',
        ownerPhone: '9876543210',
        latitude: 26.7606,
        longitude: 83.3732,
        maxDeliveryRadiusKm: 5,
      );

      // ONE CALL, AND THAT IS THE POINT. Opening a login, registering a
      // business, sending it to review, approving it and opening a shop were
      // five requests from here, and a failure at any of them left a
      // half-onboarded merchant nobody could finish or undo from this screen.
      // Server-side they are one transaction.
      expect(calls, 1);
      expect(sent!['businessName'], 'Sharma Kirana');
      expect(sent!['ownerEmail'], 'ravi@sharmakirana.test');
      expect(sent!['latitude'], 26.7606);
      expect(sent!['maxDeliveryRadiusKm'], 5);
      expect(sent!.containsKey('status'), isFalse,
          reason: 'THE LIFECYCLE IS STILL THE SERVER\'S. The merchant reaches '
              'APPROVED by being walked through PENDING_REVIEW with a reason '
              'recorded, not by this form naming an end state');
      expect(opened.shopId, 7);
      expect(opened.ownerCustomerId, 55);
      expect(opened.oneTimePassword, 'k7Rmq3xTbYw9Zc');
      expect(opened.shopCode, 'SHARMA-KIRANA',
          reason: 'the code was derived server-side from the business name, '
              'so the console has to show what it became rather than what was '
              'asked for');
    });

    test('onboarding leaves out what was not filled in, rather than sending blanks',
        () async {
      final adapter = FakeHttpClientAdapter();
      Map<String, dynamic>? sent;
      adapter.on('POST', '/api/platform/onboard', (options) {
        sent = Map<String, dynamic>.from(options.data as Map);
        return const FakeResponse({
          'merchantId': 9,
          'shopId': 7,
          'ownerCustomerId': 55,
        });
      });

      await PlatformRepository(apiClient: buildTestApiClient(adapter))
          .onboardMerchant(
        businessName: 'Sharma Kirana',
        ownerName: 'Ravi Sharma',
        ownerEmail: 'ravi@sharmakirana.test',
        ownerPhone: '',
        latitude: 26.7606,
        longitude: 83.3732,
        maxDeliveryRadiusKm: 5,
      );

      // An empty phone sent as "" would be stored as a blank number that
      // collides with the next merchant onboarded the same way - the column
      // is UNIQUE, so the second one is refused for a number nobody typed.
      expect(sent!.containsKey('ownerPhone'), isFalse);
      expect(sent!.containsKey('shopCode'), isFalse,
          reason: 'no code given means the server derives one from the '
              'business name; an empty string would be a code');
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
