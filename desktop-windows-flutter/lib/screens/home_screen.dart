// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';

import '../app_session.dart';
import '../models.dart';
import '../widgets.dart';
import 'detail_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.session});

  final AppSession session;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  static const _categories = [('ALL', '推荐'), ('COMIC', '漫剧'), ('REAL', '真人剧')];

  final List<Series> _items = [];
  String _category = 'ALL';
  bool _loading = true;
  bool _loadingMore = false;
  bool _hasMore = false;
  int _page = 1;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load({bool refresh = true}) async {
    setState(() {
      _loading = true;
      _error = null;
      _page = 1;
    });
    try {
      final result = await widget.session.api.feed(category: _category, page: 1, refresh: refresh);
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(result.items);
        _hasMore = result.hasMore;
        _loading = false;
      });
    } catch (problem) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = problem.toString();
      });
    }
  }

  Future<void> _loadMore() async {
    if (_loadingMore || !_hasMore) return;
    setState(() => _loadingMore = true);
    try {
      final result = await widget.session.api.feed(category: _category, page: _page + 1, refresh: false);
      if (!mounted) return;
      setState(() {
        final ids = _items.map((it) => it.id).toSet();
        _items.addAll(result.items.where((it) => !ids.contains(it.id)));
        _page += 1;
        _hasMore = result.hasMore;
      });
    } catch (_) {
      // Keep what is already on screen; the footer retries.
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  void _select(String category) {
    if (_category == category) return;
    setState(() => _category = category);
    unawaited(_load());
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '发现下一部好剧',
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
              TextButton.icon(
                onPressed: _loading ? null : () => _load(refresh: true),
                icon: const Icon(Icons.autorenew, size: 18),
                label: const Text('换一批'),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
          child: ChipRail(
            options: _categories,
            selected: _category,
            onSelect: _select,
            trailing: RefreshAction(loading: _loading, onPressed: _load),
          ),
        ),
        Expanded(
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _error != null
              ? StatusPanel(
                  icon: Icons.wifi_off,
                  title: '推荐加载失败',
                  message: _error!,
                  actionLabel: '重试',
                  onAction: _load,
                )
              : _items.isEmpty
              ? const StatusPanel(
                  icon: Icons.movie_filter_outlined,
                  title: '暂时没有推荐',
                  message: '换一批试试，或到「找剧」里按题材浏览。',
                )
              : Column(
                  children: [
                    if (widget.session.store.loadError != null)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                        child: ErrorBanner(message: widget.session.store.loadError!),
                      ),
                    Expanded(
                      child: CatalogGrid(
                        items: _items,
                        hasMore: _hasMore,
                        loadingMore: _loadingMore,
                        onLoadMore: _loadMore,
                        onTap: (series) => Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) => DetailScreen(session: widget.session, series: series),
                          ),
                        ),
                      ),
                    ),
                    if (widget.session.store.saveError != null)
                      Container(
                        width: double.infinity,
                        color: scheme.surfaceContainerHigh,
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
                        child: Text(
                          widget.session.store.saveError!,
                          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                        ),
                      ),
                  ],
                ),
        ),
      ],
    );
  }
}
