// SPDX-License-Identifier: GPL-3.0-only
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:guoyingtv_windows/store.dart';

/// The QR session is persisted through DPAPI via dart:ffi. This test exercises the real
/// CryptProtectData/CryptUnprotectData path on Windows so a broken FFI signature or a leaked
/// buffer is caught before the app ships, without touching the developer's `%APPDATA%`.
void main() {
  test('DPAPI round-trips the saved session', () {
    if (!Platform.isWindows) {
      return;
    }
    final directory = Directory.systemTemp.createTempSync('guoyingtv-secret-');
    final file = File('${directory.path}${Platform.pathSeparator}session.bin');
    SecretStore.debugFile = file;
    try {
      expect(SecretStore.load(), isNull);

      const session = '{"token":"abc","deviceId":"d1","expiresAt":1700000000000}';
      SecretStore.save(session);

      final protected = file.readAsBytesSync();
      expect(protected, isNotEmpty);
      // The plaintext must not be readable without DPAPI.
      expect(String.fromCharCodes(protected).contains('deviceId'), isFalse);

      expect(SecretStore.load(), session);

      // A corrupted blob is treated as "no session" instead of throwing.
      file.writeAsBytesSync([0, 1, 2, 3]);
      expect(SecretStore.load(), isNull);

      SecretStore.delete();
      expect(file.existsSync(), isFalse);
    } finally {
      SecretStore.debugFile = null;
      directory.deleteSync(recursive: true);
    }
  });
}
