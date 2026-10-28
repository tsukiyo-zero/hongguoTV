// SPDX-License-Identifier: GPL-3.0-only
import 'dart:io';

import 'package:flutter/material.dart';

import '../app_info.dart';
import '../app_session.dart';
import '../store.dart';
import '../widgets.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.session});

  final AppSession session;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: session.store,
      builder: (context, _) {
        final store = session.store;
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            Text(
              '设置',
              style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 20),
            _section(
              context,
              '外观',
              [
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(value: 'system', label: Text('跟随系统'), icon: Icon(Icons.brightness_auto)),
                    ButtonSegment(value: 'light', label: Text('浅色'), icon: Icon(Icons.light_mode)),
                    ButtonSegment(value: 'dark', label: Text('深色'), icon: Icon(Icons.dark_mode)),
                  ],
                  selected: {store.themeMode},
                  onSelectionChanged: (value) => store.updateThemeMode(value.first),
                ),
              ],
            ),
            const SizedBox(height: 20),
            _section(context, '播放设置', [
              const _SettingLabel('默认清晰度'),
              ChipRail(
                options: const [('480', '480P'), ('720', '720P'), ('1080', '1080P')],
                selected: '${store.quality}',
                onSelect: (value) => store.updateQuality(int.parse(value)),
              ),
              const SizedBox(height: 12),
              const _SettingLabel('默认倍速'),
              ChipRail(
                options: [
                  for (final speed in speeds)
                    (
                      '$speed',
                      speed == speed.roundToDouble() ? '${speed.toInt()}×' : '$speed×',
                    ),
                ],
                selected: '${store.speed}',
                onSelect: (value) => store.updateSpeed(double.parse(value)),
              ),
              const SizedBox(height: 4),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('自动下一集'),
                subtitle: const Text('本集播放结束后直接续播下一集'),
                value: store.autoNext,
                onChanged: store.updateAutoNext,
              ),
              Text(
                '清晰度与倍速会同步到播放器；播放中也可以随时切换。',
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ]),
            const SizedBox(height: 20),
            _section(context, '播放器', [
              Text(
                '内置 libmpv（media_kit）解码，支持清晰度切换、倍速、断点续播与弹幕叠加，'
                '无需单独安装 VLC。',
                style: TextStyle(
                  fontSize: 13,
                  height: 1.6,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ]),
            const SizedBox(height: 20),
            _section(context, '关于', [
              Text('${AppInfo.product} ${AppInfo.version}'),
              const SizedBox(height: 4),
              Text(
                '内容解析、登录与解密由本机 Java 子进程完成，界面只通过随机令牌的 127.0.0.1 回环接口访问。',
                style: TextStyle(
                  fontSize: 12,
                  height: 1.6,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  OutlinedButton(
                    onPressed: () => _showLicense(context),
                    child: const Text('许可与说明'),
                  ),
                  const SizedBox(width: 10),
                  OutlinedButton.icon(
                    onPressed: () => _openFolder(context, AppDirs.data.path),
                    icon: const Icon(Icons.folder_open, size: 18),
                    label: const Text('打开数据目录'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              SelectableText(
                '本机数据目录：${AppDirs.data.path}\n本机服务日志：${AppDirs.data.path}\\bridge.log',
                style: TextStyle(
                  fontSize: 11,
                  height: 1.6,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
              if (session.bridgeStatus.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(
                  session.bridgeStatus,
                  style: TextStyle(
                    fontSize: 11,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ]),
          ],
        );
      },
    );
  }

  Widget _section(BuildContext context, String title, List<Widget> children) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionTitle(title),
          ...children,
        ],
      ),
    );
  }

  void _showLicense(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('许可与说明'),
        content: const SizedBox(
          width: 460,
          child: SingleChildScrollView(
            child: Text(
              '果映 TV 桌面客户端以 GPL-3.0-only 发布，源码地址：\n'
              'https://github.com/tsukiyo-zero/hongguoTV\n\n'
              '界面使用 Flutter 与 Material 3；播放使用 media_kit / libmpv（LGPL-2.1+）；'
              '视频解析与账号逻辑复用 kotlin-tv 内核与本机 Java 回环服务。\n\n'
              '本客户端仅用于个人学习与已授权内容的播放，所有剧集版权归原权利人所有。'
              '应用不内置任何账号密码，也不会把登录态上传到第三方服务器。',
              style: TextStyle(fontSize: 13, height: 1.7),
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('关闭')),
        ],
      ),
    );
  }

  Future<void> _openFolder(BuildContext context, String path) async {
    try {
      await Process.start('explorer.exe', [path], runInShell: false);
    } catch (problem) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text('无法打开目录：$problem')));
    }
  }
}

class _SettingLabel extends StatelessWidget {
  const _SettingLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 6),
    child: Text(text, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
  );
}
