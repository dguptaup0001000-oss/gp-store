import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Flutter lib has no R2/Cloudinary/AWS secrets and no Cloudinary upload client',
      () {
    final offenders = <String>[];
    for (final file in Directory('lib')
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))) {
      final text = file.readAsStringSync();
      for (final needle in [
        'R2_SECRET_ACCESS_KEY',
        'R2_ACCESS_KEY_ID',
        'AWS_SECRET_ACCESS_KEY',
        'AWS_ACCESS_KEY_ID',
        'CLOUDINARY_API_SECRET',
        'CLOUDINARY_API_KEY',
        'api.cloudinary.com',
        'cloudinary-signature',
        'getCloudinarySignature',
        'CloudinarySignature',
      ]) {
        if (text.contains(needle)) {
          offenders.add('${file.path}: $needle');
        }
      }
    }
    expect(offenders, isEmpty, reason: offenders.join('\n'));
  });
}
