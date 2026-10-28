// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';

import '../app_session.dart';
import '../models.dart';
import '../widgets.dart';
import 'detail_screen.dart';

/// Local-only lists. They survive account changes and are stored in
/// `%APPDATA%\GuoyingTV\library.json`, matching the Compose and macOS clients.
class LibraryScreen extends StatelessWidget {
  const LibraryScreen({super.key, required this.session});

  final AppSession session;

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: AnimatedBuilder(
        animation: session.store,
        builder: (context, _) {
          final store = session.store;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        '本机片单',
                        style: Theme.of(
                          context,
                        ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    Text(
                      '收藏 ${store.favorites.length} · 记录 ${store.history.length}',
                      style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              const TabBar(
                isScrollable: true,
                tabAlignment: TabAlignment.start,
                tabs: [Tab(text: '本机收藏'), Tab(text: '观看记录')],
              ),
              if (store.loadError != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                  child: ErrorBanner(message: store.loadError!),
                ),
              Expanded(
                child: TabBarView(children: [_favorites(), _history()]),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _favorites() {
    final favorites = session.store.favorites;
    if (favorites.isEmpty) {
      return const StatusPanel(
        icon: Icons.star_border,
        title: '还没有本机收藏',
        message: '在详情页点星标即可加入本机收藏。',
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) => GridView.builder(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        gridDelegate: dramaGridDelegate(context, constraints.maxWidth),
        itemCount: favorites.length,
        itemBuilder: (context, index) {
          final series = favorites[index];
          return DramaTile(
            series: series,
            onTap: () => _open(context, series),
            onSecondaryTap: () => _remove(context, series),
            trailing: _removeButton(context, series),
          );
        },
      ),
    );
  }

  Widget _removeButton(BuildContext context, Series series) {
    return Material(
      color: Colors.black45,
      shape: const CircleBorder(),
      child: IconButton(
        tooltip: '取消收藏',
        visualDensity: VisualDensity.compact,
        iconSize: 16,
        color: Colors.white,
        onPressed: () => _remove(context, series),
        icon: const Icon(Icons.close),
      ),
    );
  }

  void _remove(BuildContext context, Series series) {
    session.store.toggleFavorite(series);
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text('已取消收藏《${series.title}》')));
  }

  Widget _history() {
    final history = session.store.history;
    if (history.isEmpty) {
      return const StatusPanel(
        icon: Icons.history,
        title: '还没有观看记录',
        message: '正式观看后会自动保存进度，下次可以从这里继续。',
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
      itemCount: history.length,
      separatorBuilder: (_, _) => const SizedBox(height: 10),
      itemBuilder: (context, index) => _HistoryRow(
        record: history[index],
        onOpen: () => _open(context, history[index].series),
        onRemove: () => _confirmRemove(context, history[index]),
      ),
    );
  }

  void _open(BuildContext context, Series series) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => DetailScreen(session: session, series: series),
      ),
    );
  }

  Future<void> _confirmRemove(BuildContext context, WatchRecord record) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除观看记录'),
        content: Text('将删除《${record.series.title}》的本机进度，账号进度不受影响。'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('删除')),
        ],
      ),
    );
    if (confirmed == true) session.store.removeHistory(record.series.id);
  }
}

class _HistoryRow extends StatelessWidget {
  const _HistoryRow({required this.record, required this.onOpen, required this.onRemove});

  final WatchRecord record;
  final VoidCallback onOpen;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final progress = record.duration > 0 ? (record.position / record.duration).clamp(0.0, 1.0) : 0.0;
    return InkWell(
      onTap: onOpen,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            SizedBox(width: 56, child: DramaCover(series: record.series, radius: 10)),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    record.series.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    record.label,
                    style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                  ),
                  const SizedBox(height: 8),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(3),
                    child: LinearProgressIndicator(
                      value: progress,
                      minHeight: 4,
                      backgroundColor: scheme.surfaceContainerHighest,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Text(
              _updatedLabel(record.updatedAt),
              style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
            ),
            IconButton(
              tooltip: '继续观看',
              onPressed: onOpen,
              icon: const Icon(Icons.play_circle_outline, size: 22),
            ),
            IconButton(
              tooltip: '删除记录',
              onPressed: onRemove,
              icon: const Icon(Icons.delete_outline, size: 20),
            ),
          ],
        ),
      ),
    );
  }

  static String _updatedLabel(int millis) {
    if (millis <= 0) return '';
    final time = DateTime.fromMillisecondsSinceEpoch(millis);
    String two(int value) => value.toString().padLeft(2, '0');
    return '${time.year}-${two(time.month)}-${two(time.day)} ${two(time.hour)}:${two(time.minute)}';
  }
}
