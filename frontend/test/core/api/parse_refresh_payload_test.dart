import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/core/api/api_client.dart';

void main() {
  test('a real refresh body yields both tokens', () {
    final parsed = parseRefreshPayload({
      'token': 'access-1',
      'refreshToken': 'refresh-1',
    });
    expect(parsed?.access, 'access-1');
    expect(parsed?.refresh, 'refresh-1');
  });

  test('HTML or a missing field is not treated as a JWT pair', () {
    expect(parseRefreshPayload('<html>error</html>'), isNull);
    expect(parseRefreshPayload({'token': 'only-access'}), isNull);
    expect(parseRefreshPayload({'token': '', 'refreshToken': 'x'}), isNull);
    expect(parseRefreshPayload(null), isNull);
  });
}
