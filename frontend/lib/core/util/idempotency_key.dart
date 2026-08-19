import 'dart:math';

/// Generates the value sent as the `Idempotency-Key` header when placing an
/// order.
///
/// Written by hand rather than adding the `uuid` package: this needs exactly
/// one function, and this project's pubspec is deliberately tightly pinned
/// (see the comments there - several packages are held back to versions the
/// pinned Flutter/analyzer chain can actually build). A new transitive
/// dependency is a real risk to that build for no benefit here.
///
/// Random.secure() rather than Random(): these keys must not be guessable or
/// collide across devices. A collision between two customers is harmless -
/// keys are scoped per customer server-side - but a collision within one
/// customer's own account would make a genuinely new checkout look like a
/// retry of an old one, and the server would replay the old order instead of
/// placing the new one.
///
/// Format is RFC 4122 version 4: 122 random bits, with the version nibble
/// and variant bits fixed. The server treats it as an opaque string, so the
/// format matters only for being recognisably a UUID in logs.
String generateIdempotencyKey() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));

  // Version 4 (random): high nibble of byte 6 must be 0100.
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  // Variant RFC 4122: top two bits of byte 8 must be 10.
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  String hex(int start, int end) => bytes
      .sublist(start, end)
      .map((b) => b.toRadixString(16).padLeft(2, '0'))
      .join();

  return '${hex(0, 4)}-${hex(4, 6)}-${hex(6, 8)}-${hex(8, 10)}-${hex(10, 16)}';
}
