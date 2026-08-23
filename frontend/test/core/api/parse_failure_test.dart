import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/error_messages.dart';
import 'package:gpstore/features/products/domain/product_models.dart';

/// A response the app cannot parse must not read as "you did something wrong".
///
/// The admin app created products successfully and then reported failure on
/// every one of them. The backend answered 200 with
/// "category":{"id":1,"name":null,...} - a stub built from the request body,
/// never resolved to the real row - and Category declares `required String
/// name`, so fromJson threw before the screen saw anything.
///
/// The admin retried. The retry created a second product. The live catalogue
/// ended up with two "machar bati" rows and two spellings of "pooja bati".
///
/// The backend fix (see CategoryInProductResponseTest) stops the null. These
/// tests keep the SECOND line of defence honest: if any endpoint ever returns
/// a shape the models cannot read, the message must say the app could not
/// read the reply and that the change may already have been saved - never
/// "something went wrong", which sends an admin back to retype correct input
/// and duplicate a row.
void main() {
  test('a null in a required field is exactly what used to happen', () {
    // Documents the contract this bug broke: the model is strict on purpose.
    expect(
      () => Category.fromJson(const {
        'id': 1,
        'name': null,
        'description': null,
        'imageUrl': null,
        'gstRate': null,
        'active': null,
      }),
      throwsA(isA<TypeError>()),
      reason: 'Category.name is required; a null must fail loudly rather than '
          'silently produce a half-built category',
    );
  });

  test('a parse failure is reported as the app\'s fault, not the admin\'s', () {
    late Object thrown;
    try {
      Category.fromJson(const {'id': 1, 'name': null});
    } catch (e) {
      thrown = e;
    }

    final message = extractErrorMessage(thrown);

    expect(message, isNot('Something went wrong. Please try again.'),
        reason: 'the generic sentence is what made this cost days');
    expect(message.toLowerCase(), contains('could not read'));
    // The most important half: warn before a retry duplicates the row.
    expect(message.toLowerCase(), contains('may already have been saved'));
  });

  test('a well-formed category still parses, so the guard has not gone too far', () {
    final category = Category.fromJson(const {
      'id': 1,
      'name': 'Atta, Rice & Dal',
      'description': 'everyday kirana essentials',
      'imageUrl': null,
      'gstRate': null,
      'active': true,
    });

    expect(category.id, 1);
    expect(category.name, 'Atta, Rice & Dal');
    expect(category.active, isTrue);
  });
}
