import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/admin_customer_detail_model.dart';

/// How riders have found a customer at the door, as the shop screen reads it.
///
/// THE DISTINCTION THAT MATTERS. Zero is the worst score there is, and "no
/// rider has ever rated this person" is not a score at all. Collapsing the
/// two would put a customer at the bottom of the shop's list for having done
/// nothing, so the model keeps a null average null and the screen renders the
/// two differently.
void main() {
  test('an average and a count come through', () {
    final conduct = CustomerConduct.fromJson(const {
      'averageScore': 7.5,
      'ratedDeliveries': 4,
      'recent': [
        {'orderId': 91, 'score': 9, 'ratedAt': '2026-09-01T18:20:00'},
        {'orderId': 88, 'score': 6, 'ratedAt': '2026-08-28T17:05:00'},
      ],
    });

    expect(conduct.hasRatings, isTrue);
    expect(conduct.averageScore, 7.5);
    expect(conduct.ratedDeliveries, 4);
    expect(conduct.recent, hasLength(2));
    expect(conduct.recent.first.orderId, 91);
    expect(conduct.recent.first.ratedAt, DateTime(2026, 9, 1, 18, 20));
  });

  test('never rated is not a score of zero', () {
    final conduct = CustomerConduct.fromJson(const {
      'averageScore': null,
      'ratedDeliveries': 0,
      'recent': <Map<String, dynamic>>[],
    });

    expect(conduct.averageScore, isNull,
        reason: 'a default of 0 here would read as the worst possible customer');
    expect(conduct.hasRatings, isFalse);
    expect(conduct.ratedDeliveries, 0);
  });

  test('a genuinely terrible average is still shown', () {
    // The other direction of the same rule: 1.0 is real information and must
    // not be hidden by the same guard that hides "unknown".
    final conduct = CustomerConduct.fromJson(const {
      'averageScore': 1.0,
      'ratedDeliveries': 3,
      'recent': <Map<String, dynamic>>[],
    });

    expect(conduct.hasRatings, isTrue);
    expect(conduct.averageScore, 1.0);
  });

  test('an integer average from the server is read as a number, not dropped', () {
    // avg() over whole scores can serialise as 8 rather than 8.0.
    final conduct = CustomerConduct.fromJson(const {
      'averageScore': 8,
      'ratedDeliveries': 2,
      'recent': <Map<String, dynamic>>[],
    });

    expect(conduct.averageScore, 8.0);
    expect(conduct.hasRatings, isTrue);
  });

  test('a server that sends no conduct block at all still parses', () {
    // Additive field: an app build ahead of the server must not throw, or the
    // whole customer screen breaks over a section nobody asked for.
    final detail = AdminCustomerDetail.fromJson(const {
      'id': 1,
      'fullName': 'A Customer',
      'active': true,
      'verified': true,
    });

    expect(detail.conduct.hasRatings, isFalse);
    expect(detail.conduct.recent, isEmpty);
  });
}
