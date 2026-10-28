// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:media_kit/media_kit.dart';
import 'package:window_manager/window_manager.dart';

import 'api.dart';
import 'app_info.dart';
import 'app_session.dart';
import 'bridge.dart';
import 'shell.dart';
import 'store.dart';
import 'theme.dart';
import 'widgets.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  MediaKit.ensureInitialized();
  await windowManager.ensureInitialized();
  const options = WindowOptions(
    size: Size(1360, 860),
    minimumSize: Size(1080, 700),
    center: true,
    title: '果映 TV',
    backgroundColor: Colors.transparent,
    titleBarStyle: TitleBarStyle.normal,
  );
  await windowManager.waitUntilReadyToShow(options, () async {
    await windowManager.setPreventClose(true);
    await windowManager.show();
    await windowManager.focus();
  });
  runApp(GuoyingApp(store: LocalStore.load()));
}

class GuoyingApp extends StatefulWidget {
  const GuoyingApp({super.key, required this.store});

  final LocalStore store;

  @override
  State<GuoyingApp> createState() => _GuoyingAppState();
}

class _GuoyingAppState extends State<GuoyingApp> with WindowListener {
  Bridge? _bridge;
  AppSession? _session;
  String? _error;
  bool _starting = true;

  @override
  void initState() {
    super.initState();
    windowManager.addListener(this);
    _start();
  }

  @override
  void dispose() {
    windowManager.removeListener(this);
    super.dispose();
  }

  Future<void> _start() async {
    try {
      await _bridge?.close();
    } catch (_) {
      // The previous process may already be gone.
    }
    _bridge = null;
    setState(() {
      _starting = true;
      _error = null;
      _session = null;
    });
    Bridge? bridge;
    try {
      bridge = await Bridge.launch();
      final session = AppSession(api: Api(bridge), store: widget.store)
        ..setBridgeStatus('本机服务已连接');
      if (!mounted) return;
      setState(() {
        _bridge = bridge;
        _session = session;
        _starting = false;
      });
      // Account validation hits the network, so the shell comes up first and the chip
      // settles once the stored DPAPI session has been restored.
      unawaited(_restoreAccount(session));
    } catch (problem) {
      try {
        await bridge?.close();
      } catch (_) {
        // The process may already be gone.
      }
      if (!mounted) return;
      setState(() {
        _starting = false;
        _error = problem.toString();
      });
    }
  }

  Future<void> _restoreAccount(AppSession session) async {
    final saved = SecretStore.load();
    if (saved == null || saved.isEmpty) return;
    try {
      final info = await session.api.restoreAccount(saved);
      if (!mounted) return;
      if (info.loggedIn) {
        session.setAccount(info);
      } else {
        session.setBridgeStatus('本机服务已连接 · 登录态已失效，请重新扫码');
      }
    } catch (_) {
      session.setBridgeStatus('本机服务已连接 · 登录态已失效，请重新扫码');
    }
  }

  @override
  Future<void> onWindowClose() async {
    await _bridge?.close();
    await windowManager.destroy();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.store,
      builder: (context, _) => MaterialApp(
        title: AppInfo.name,
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light,
        darkTheme: AppTheme.dark,
        themeMode: AppTheme.mode(widget.store.themeMode),
        locale: const Locale('zh', 'CN'),
        supportedLocales: const [Locale('zh', 'CN')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: _home(),
      ),
    );
  }

  Widget _home() {
    if (_error != null) return _StartupError(message: _error!, onRetry: _start);
    final session = _session;
    if (_starting || session == null) return const _Splash();
    return Shell(session: session);
  }
}

class _Splash extends StatelessWidget {
  const _Splash();

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: scheme.primaryContainer,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Icon(Icons.play_circle_fill, size: 36, color: scheme.primary),
            ),
            const SizedBox(height: 18),
            Text(
              '果映 TV',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                fontWeight: FontWeight.w800,
                color: scheme.primary,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              '正在启动本机服务…',
              style: TextStyle(fontSize: 13, color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 24),
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2.5),
            ),
            const SizedBox(height: 20),
            Text(
              '首次启动需要解压播放组件，请稍候。',
              style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }
}

class _StartupError extends StatelessWidget {
  const _StartupError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              StatusPanel(
                icon: Icons.error_outline,
                title: '本机服务启动失败',
                message: message,
                actionLabel: '重试',
                onAction: onRetry,
              ),
              Text(
                '服务日志：${AppDirs.data.path}\\bridge.log',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
              ),
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: () => Process.start('explorer.exe', [AppDirs.data.path], runInShell: false),
                icon: const Icon(Icons.folder_open, size: 18),
                label: const Text('打开数据目录'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
