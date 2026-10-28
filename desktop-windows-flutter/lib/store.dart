// SPDX-License-Identifier: GPL-3.0-only
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';
import 'package:flutter/foundation.dart';
import 'package:win32/win32.dart';

import 'models.dart';

abstract final class AppDirs {
  static Directory get data {
    final base = Platform.environment['APPDATA'];
    final path = base != null && base.isNotEmpty
        ? '$base${Platform.pathSeparator}GuoyingTV'
        : '${Directory.current.path}${Platform.pathSeparator}GuoyingTV';
    return Directory(path);
  }

  static void ensure() {
    final directory = data;
    if (!directory.existsSync()) directory.createSync(recursive: true);
  }
}

const speeds = <double>[0.5, 0.75, 1, 1.25, 1.5, 2];

/// Local favorites, watch history and playback preferences. The JSON schema matches the
/// Compose/macOS desktop clients so an existing `library.json` keeps working after the
/// Flutter client replaces them.
class LocalStore extends ChangeNotifier {
  LocalStore._();

  final List<Series> _favorites = [];
  final List<WatchRecord> _history = [];

  int quality = 1080;
  double speed = 1;
  bool autoNext = true;
  String themeMode = 'system';
  String? loadError;
  String? saveError;

  List<Series> get favorites => List.unmodifiable(_favorites);
  List<WatchRecord> get history => List.unmodifiable(_history);

  static LocalStore load() {
    AppDirs.ensure();
    final store = LocalStore._();
    store._loadLibrary();
    store._loadPreferences();
    return store;
  }

  File get _libraryFile =>
      File('${AppDirs.data.path}${Platform.pathSeparator}library.json');

  File get _settingsFile =>
      File('${AppDirs.data.path}${Platform.pathSeparator}settings.json');

  void _loadLibrary() {
    final file = _libraryFile;
    if (!file.existsSync()) return;
    try {
      final bytes = file.readAsBytesSync();
      if (bytes.length > 8 * 1024 * 1024) throw const FormatException('文件过大');
      final root = jsonDecode(utf8.decode(bytes));
      if (root is! Map || (root['schema'] as num?)?.toInt() != 1) {
        throw const FormatException('版本不兼容');
      }
      for (final item in (root['favorites'] as List?) ?? const []) {
        if (item is Map) _favorites.add(Series.fromJson(Map<String, dynamic>.from(item)));
      }
      for (final item in (root['history'] as List?) ?? const []) {
        if (item is! Map) continue;
        try {
          _history.add(WatchRecord.fromJson(Map<String, dynamic>.from(item)));
        } catch (_) {
          // Skip a malformed row instead of discarding the whole history.
        }
      }
    } catch (_) {
      loadError = '本机记录读取失败，已保留原文件；请备份后检查。';
    }
  }

  void _loadPreferences() {
    final file = _settingsFile;
    if (!file.existsSync()) return;
    try {
      final root = jsonDecode(utf8.decode(file.readAsBytesSync()));
      if (root is! Map) return;
      final savedQuality = (root['quality'] as num?)?.toInt() ?? 1080;
      quality = const {480, 720, 1080}.contains(savedQuality) ? savedQuality : 1080;
      final savedSpeed = (root['speed'] as num?)?.toDouble() ?? 1;
      speed = speeds.contains(savedSpeed) ? savedSpeed : 1;
      autoNext = root['autoNext'] as bool? ?? true;
      final mode = root['themeMode'] as String? ?? 'system';
      themeMode = const {'light', 'dark', 'system'}.contains(mode) ? mode : 'system';
    } catch (_) {
      // Preferences are disposable: fall back to defaults.
    }
  }

  bool isFavorite(String id) => _favorites.any((it) => it.id == id);

  WatchRecord? recordFor(String id) {
    for (final record in _history) {
      if (record.series.id == id) return record;
    }
    return null;
  }

  void toggleFavorite(Series series) {
    final index = _favorites.indexWhere((it) => it.id == series.id);
    if (index >= 0) {
      _favorites.removeAt(index);
    } else {
      _favorites.insert(0, series);
      if (_favorites.length > 2000) _favorites.removeRange(2000, _favorites.length);
    }
    _saveLibrary();
    notifyListeners();
  }

  void record(WatchRecord value) {
    if (!value.position.isFinite || !value.duration.isFinite) return;
    if (value.position < 0 || value.duration < 0 || value.episodeIndex < 0) return;
    if (value.episodeId.isEmpty) return;
    _history.removeWhere((it) => it.series.id == value.series.id);
    _history.insert(0, value);
    if (_history.length > 500) _history.removeRange(500, _history.length);
    _saveLibrary();
    notifyListeners();
  }

  void removeHistory(String seriesId) {
    _history.removeWhere((it) => it.series.id == seriesId);
    _saveLibrary();
    notifyListeners();
  }

  void updateQuality(int value) {
    if (!const {480, 720, 1080}.contains(value)) return;
    quality = value;
    _savePreferences();
    notifyListeners();
  }

  void updateSpeed(double value) {
    if (!speeds.contains(value)) return;
    speed = value;
    _savePreferences();
    notifyListeners();
  }

  void updateAutoNext(bool value) {
    autoNext = value;
    _savePreferences();
    notifyListeners();
  }

  void updateThemeMode(String value) {
    if (!const {'light', 'dark', 'system'}.contains(value)) return;
    themeMode = value;
    _savePreferences();
    notifyListeners();
  }

  void _saveLibrary() {
    try {
      _writeAtomically(
        _libraryFile,
        utf8.encode(
          jsonEncode({
            'schema': 1,
            'favorites': _favorites.map((it) => it.toJson()).toList(),
            'history': _history.map((it) => it.toJson()).toList(),
          }),
        ),
      );
      saveError = null;
    } catch (_) {
      saveError = '本机记录保存失败，请检查磁盘空间。';
    }
  }

  void _savePreferences() {
    try {
      _writeAtomically(
        _settingsFile,
        utf8.encode(
          jsonEncode({
            'quality': quality,
            'speed': speed,
            'autoNext': autoNext,
            'themeMode': themeMode,
          }),
        ),
      );
    } catch (_) {
      saveError = '设置保存失败，请检查磁盘空间。';
    }
  }
}

void _writeAtomically(File file, List<int> bytes) {
  file.parent.createSync(recursive: true);
  final temporary = File('${file.path}.tmp');
  temporary.writeAsBytesSync(bytes, flush: true);
  if (file.existsSync()) file.deleteSync();
  temporary.renameSync(file.path);
}

/// DPAPI-encrypted QR session. The file alone is useless on another Windows account or PC,
/// which matches the Compose client's `session.bin`.
abstract final class SecretStore {
  static File get _file => File('${AppDirs.data.path}${Platform.pathSeparator}session.bin');

  static String? load() {
    final file = _file;
    if (!file.existsSync()) return null;
    try {
      final protected = file.readAsBytesSync();
      if (protected.isEmpty) return null;
      return utf8.decode(_unprotect(protected));
    } catch (_) {
      return null;
    }
  }

  static void save(String value) {
    AppDirs.ensure();
    _writeAtomically(_file, _protect(Uint8List.fromList(utf8.encode(value))));
  }

  static void delete() {
    try {
      final file = _file;
      if (file.existsSync()) file.deleteSync();
    } catch (_) {
      // Ignore: the session is already unusable.
    }
  }
}

const _cryptProtectUiForbidden = 0x1;

Uint8List _protect(Uint8List data) {
  final input = calloc<CRYPT_INTEGER_BLOB>();
  final output = calloc<CRYPT_INTEGER_BLOB>();
  final buffer = calloc<Uint8>(data.length);
  try {
    buffer.asTypedList(data.length).setAll(0, data);
    input.ref.cbData = data.length;
    input.ref.pbData = buffer;
    final result = CryptProtectData(
      input,
      null,
      null,
      null,
      _cryptProtectUiForbidden,
      output,
    );
    if (!result.value) throw StateError('CryptProtectData failed: ${result.error}');
    return Uint8List.fromList(output.ref.pbData.asTypedList(output.ref.cbData));
  } finally {
    if (output.ref.pbData != nullptr) LocalFree(HLOCAL(output.ref.pbData));
    calloc.free(buffer);
    calloc.free(input);
    calloc.free(output);
  }
}

Uint8List _unprotect(Uint8List data) {
  final input = calloc<CRYPT_INTEGER_BLOB>();
  final output = calloc<CRYPT_INTEGER_BLOB>();
  final buffer = calloc<Uint8>(data.length);
  try {
    buffer.asTypedList(data.length).setAll(0, data);
    input.ref.cbData = data.length;
    input.ref.pbData = buffer;
    final result = CryptUnprotectData(
      input,
      nullptr,
      nullptr,
      nullptr,
      _cryptProtectUiForbidden,
      output,
    );
    if (!result.value) throw StateError('CryptUnprotectData failed: ${result.error}');
    return Uint8List.fromList(output.ref.pbData.asTypedList(output.ref.cbData));
  } finally {
    if (output.ref.pbData != nullptr) LocalFree(HLOCAL(output.ref.pbData));
    calloc.free(buffer);
    calloc.free(input);
    calloc.free(output);
  }
}
