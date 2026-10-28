// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import 'store.dart';

class BridgeException implements Exception {
  BridgeException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Loopback bridge shared with the macOS client: a child JVM that owns all site parsing,
/// signing, decryption and account logic, reached only through `127.0.0.1` with a random
/// path token issued on startup.
class Bridge {
  Bridge._(this.baseUrl, this._process, this._log, this._client);

  final String baseUrl;
  final Process _process;
  final IOSink _log;
  final http.Client _client;

  static Future<Bridge> launch() async {
    final root = _appRoot();
    final bridgeDir = _bridgeDir(root);
    final java = _javaExecutable(root);
    AppDirs.ensure();
    final log = File('${AppDirs.data.path}${Platform.pathSeparator}bridge.log').openWrite();
    log.writeln('--- ${DateTime.now().toIso8601String()} ---');
    log.writeln('java=$java');
    log.writeln('bridge=$bridgeDir');

    final process = await Process.start(
      java,
      [
        '--add-modules',
        'jdk.httpserver,jdk.crypto.ec',
        '-Dfile.encoding=UTF-8',
        '-cp',
        '$bridgeDir${Platform.pathSeparator}*',
        'com.hongguotv.desktop.MainKt',
      ],
      workingDirectory: bridgeDir,
      runInShell: false,
    );
    process.stderr.transform(utf8.decoder).listen(log.writeln);

    final completer = Completer<String>();
    late final StreamSubscription<String> subscription;
    subscription = process.stdout
        .transform(utf8.decoder)
        .transform(const LineSplitter())
        .listen((line) {
          if (!completer.isCompleted && line.startsWith('http://127.0.0.1:')) {
            completer.complete(line.trim());
          } else if (line.trim().isNotEmpty) {
            log.writeln(line);
          }
        });
    unawaited(
      process.exitCode.then((code) {
        log.writeln('bridge exited with $code');
        if (!completer.isCompleted) {
          completer.completeError(BridgeException('本机服务已退出（$code），请重新打开客户端。'));
        }
      }),
    );

    final url = await completer.future.timeout(
      const Duration(seconds: 60),
      onTimeout: () {
        process.kill();
        throw BridgeException('本机服务启动超时，请查看 bridge.log 后重试。');
      },
    );
    await subscription.cancel();
    return Bridge._(url.endsWith('/') ? url : '$url/', process, log, http.Client());
  }

  Future<Map<String, dynamic>> get(String path, [Map<String, String>? query]) =>
      _send(path, query: query);

  Future<Map<String, dynamic>> post(String path, [Map<String, dynamic>? body]) =>
      _send(path, body: body);

  Future<Map<String, dynamic>> _send(
    String path, {
    Map<String, String>? query,
    Map<String, dynamic>? body,
  }) async {
    final uri = Uri.parse('$baseUrl$path').replace(queryParameters: query);
    try {
      final response = body == null
          ? await _client.get(uri).timeout(const Duration(seconds: 60))
          : await _client
                .post(
                  uri,
                  headers: const {'Content-Type': 'application/json; charset=utf-8'},
                  body: utf8.encode(jsonEncode(body)),
                )
                .timeout(const Duration(seconds: 60));
      final text = utf8.decode(response.bodyBytes, allowMalformed: true);
      dynamic decoded;
      try {
        decoded = text.isEmpty ? <String, dynamic>{} : jsonDecode(text);
      } on FormatException {
        throw BridgeException('本机服务返回了无法解析的数据，请重试。');
      }
      if (response.statusCode != 200) {
        final message = decoded is Map ? decoded['error'] as String? : null;
        throw BridgeException(message?.isNotEmpty == true ? message! : '请求失败（${response.statusCode}）。');
      }
      if (decoded is Map) return Map<String, dynamic>.from(decoded);
      throw BridgeException('本机服务返回了意外的数据格式。');
    } on BridgeException {
      rethrow;
    } on TimeoutException {
      throw BridgeException('请求超时，请检查网络后重试。');
    } on SocketException {
      throw BridgeException('本机服务连接中断，请重新打开客户端。');
    } on http.ClientException {
      throw BridgeException('本机服务连接中断，请重新打开客户端。');
    }
  }

  Future<void> close() async {
    _client.close();
    try {
      await _process.stdin.close();
    } catch (_) {
      // The bridge may already have exited.
    }
    await Future<void>.delayed(const Duration(milliseconds: 120));
    _process.kill();
    await _log.flush().catchError((_) {});
    await _log.close().catchError((_) {});
  }

  static String _appRoot() {
    final exeDir = File(Platform.resolvedExecutable).parent;
    final separator = Platform.pathSeparator;
    // Packaged layout: <app>\guoyingtv_windows.exe with runtime\ and bridge\ beside it.
    if (Directory('${exeDir.path}${separator}bridge').existsSync()) return exeDir.path;
    // `flutter run` layout: walk up from build\windows\x64\runner\Debug to the repository.
    var directory = exeDir;
    for (var depth = 0; depth < 8; depth++) {
      if (_bridgeCandidates(directory.path).isNotEmpty) return directory.path;
      final parent = directory.parent;
      if (parent.path == directory.path) break;
      directory = parent;
    }
    return Directory.current.path;
  }

  static List<String> _bridgeCandidates(String root) {
    final separator = Platform.pathSeparator;
    final override = Platform.environment['GUOYING_BRIDGE_DIR'];
    return <String>[
      if (override != null && override.isNotEmpty) override,
      '$root${separator}bridge',
      '$root${separator}desktop-mac${separator}backend${separator}build${separator}install${separator}bridge${separator}lib',
      '$root${separator}desktop-mac${separator}backend${separator}build${separator}install${separator}desktop${separator}lib',
    ];
  }

  static String _bridgeDir(String root) {
    for (final candidate in _bridgeCandidates(root)) {
      final directory = Directory(candidate);
      if (!directory.existsSync()) continue;
      final jars = directory
          .listSync()
          .whereType<File>()
          .where((file) => file.path.toLowerCase().endsWith('.jar'));
      if (jars.any((file) {
        final name = file.uri.pathSegments.last.toLowerCase();
        return name == 'bridge.jar' || name == 'desktop.jar';
      })) {
        return candidate;
      }
    }
    throw BridgeException('找不到本机服务文件（bridge\\*.jar），请重新安装客户端。');
  }

  static String _javaExecutable(String root) {
    final separator = Platform.pathSeparator;
    final packaged = File('$root${separator}runtime${separator}bin${separator}java.exe');
    if (packaged.existsSync()) return packaged.path;
    final home = Platform.environment['JAVA_HOME'];
    if (home != null && home.isNotEmpty) {
      final candidate = File('$home${separator}bin${separator}java.exe');
      if (candidate.existsSync()) return candidate.path;
    }
    return 'java';
  }
}
