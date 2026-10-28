// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';

import '../app_session.dart';
import '../models.dart';
import '../widgets.dart';
import 'detail_screen.dart';

class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key, required this.session, required this.query});

  final AppSession session;
  final String query;

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  final List<Series> _items = [];
  bool _loading = false;
  bool _loadingMore = false;
  bool _hasMore = false;
  int _page = 1;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.query.trim().isNotEmpty) unawaited(_load());
  }

  Future<void> _load({bool more = false}) async {
    final query = widget.query.trim();
    if (query.isEmpty) return;
    if (more && (_loadingMore || !_hasMore)) return;
    final page = more ? _page + 1 : 1;
    setState(() {
      if (more) {
        _loadingMore = true;
      } else {
        _loading = true;
        _error = null;
      }
    });
    try {
      final result = await widget.session.api.search(query, page: page);
      if (!mounted) return;
      setState(() {
        if (more) {
          final ids = _items.map((it) => it.id).toSet();
          _items.addAll(result.items.where((it) => !ids.contains(it.id)));
        } else {
          _items
            ..clear()
            ..addAll(result.items);
        }
        _page = page;
        _hasMore = result.hasMore;
        _loading = false;
        _loadingMore = false;
      });
    } catch (problem) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadingMore = false;
        if (!more) _error = problem.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    if (widget.query.trim().isEmpty) {
      return const StatusPanel(
        icon: Icons.search,
        title: '找一部想看的剧',
        message: '在窗口顶部输入剧名后按回车。',
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '“${widget.query.trim()}” 的搜索结果',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
              if (!_loading)
                Text(
                  '${_items.length} 部',
                  style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                ),
              RefreshAction(loading: _loading, onPressed: _load),
            ],
          ),
        ),
        Expanded(
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _error != null
              ? StatusPanel(
                  icon: Icons.cloud_off,
                  title: '搜索失败',
                  message: _error!,
                  actionLabel: '重试',
                  onAction: _load,
                )
              : _items.isEmpty
              ? StatusPanel(
                  icon: Icons.search_off,
                  title: '没有找到相关剧集',
                  message: '换一个关键词，或到「找剧」按类型浏览。',
                )
              : CatalogGrid(
                  items: _items,
                  hasMore: _hasMore,
                  loadingMore: _loadingMore,
                  onLoadMore: () => _load(more: true),
                  onTap: (series) => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => DetailScreen(session: widget.session, series: series),
                    ),
                  ),
                ),
        ),
      ],
    );
  }
}
