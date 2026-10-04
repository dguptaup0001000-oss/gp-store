import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('all release manifests disable app-data backup and transfer', () {
    final shared = File('android/app/src/main/AndroidManifest.xml').readAsStringSync();
    final worker = File('android/app/src/workerStandalone/AndroidManifest.xml')
        .readAsStringSync();
    final backup = File('android/app/src/main/res/xml/backup_rules.xml')
        .readAsStringSync();
    final extraction =
        File('android/app/src/main/res/xml/data_extraction_rules.xml')
            .readAsStringSync();

    for (final manifest in [shared, worker]) {
      expect(manifest, contains('android:allowBackup="false"'));
      expect(manifest, contains('android:fullBackupContent="@xml/backup_rules"'));
      expect(manifest,
          contains('android:dataExtractionRules="@xml/data_extraction_rules"'));
    }
    for (final rules in [backup, extraction]) {
      expect(rules, contains('domain="sharedpref" path="."'));
      expect(rules, contains('domain="database" path="."'));
      expect(rules, contains('domain="file" path="."'));
    }
  });

  test('Super Admin strips shop-counter Bluetooth permissions', () {
    final manifest =
        File('android/app/src/superadmin/AndroidManifest.xml').readAsStringSync();
    for (final permission in [
      'BLUETOOTH',
      'BLUETOOTH_ADMIN',
      'BLUETOOTH_CONNECT',
      'BLUETOOTH_SCAN',
    ]) {
      expect(manifest, contains('android.permission.$permission'));
      expect(manifest, contains('tools:node="remove"'));
    }
  });
}
