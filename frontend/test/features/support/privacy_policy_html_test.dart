import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('the public privacy policy matches actual GPS behaviour', () {
    final html = File('web/privacy-policy.html').readAsStringSync();
    expect(html, contains('com.gpstore.app'));
    expect(html, contains('com.gpstore.worker'));
    expect(html, contains('delivery dashboard'));
    expect(html, contains('foreground'));
    expect(html.toLowerCase(), isNot(contains('we sell personal')));
    expect(html, contains('background location permission'));
  });
}
