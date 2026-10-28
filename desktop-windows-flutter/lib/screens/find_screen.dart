// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';

import '../app_session.dart';
import '../models.dart';
import '../widgets.dart';
import 'detail_screen.dart';

/// Category/topic browser for the whole catalog. Mirrors the old client's「找剧」section:
/// pick 漫剧 / 真人剧 / AI 剧, narrow by topic and page through the list.
class FindScreen extends StatefulWidget {
  const FindScreen({super.key, required this.session});

  final AppSession session;

  @override
  State<FindScreen> createState() => _FindScreenState();
}

class _FindScreenState extends State<FindScreen> {
  static const _categories = [('COMIC', '漫剧'), ('REAL', '真人剧'), ('AI', 'AI 剧')];

  final List<Series> _items = [];
  List<Topic> _topics = const [];
  String _category = 'COMIC';
  String _topic = '';
  int _page = 1;
  bool _hasMore = false;
  bool _loading = true;
  bool _loadingMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load({bool more = false}) async {
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
      final result = await widget.session.api.catalog(_category, topic: _topic, page: page);
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
        if (result.topics.isNotEmpty) _topics = result.topics;
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

  void _selectCategory(String value) {
    if (_category == value) return;
    setState(() {
      _category = value;
      _topic = '';
      _topics = const [];
    });
    unawaited(_load());
  }

  void _selectTopic(String value) {
    if (_topic == value) return;
    setState(() => _topic = value);
    unawaited(_load());
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '按类型找剧',
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
              RefreshAction(loading: _loading, onPressed: _load),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
          child: ChipRail(
            options: _categories,
            selected: _category,
            onSelect: _selectCategory,
          ),
        ),
        if (_topics.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
            child: ChipRail(
              options: [('', '全部题材'), ..._topics.map((it) => (it.id, it.label))],
              selected: _topic,
              onSelect: _selectTopic,
            ),
          ),
        Expanded(
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _error != null
              ? StatusPanel(
                  icon: Icons.grid_view,
                  title: '剧集列表加载失败',
                  message: _error!,
                  actionLabel: '重试',
                  onAction: _load,
                )
              : _items.isEmpty
              ? StatusPanel(
                  icon: Icons.grid_view,
                  title: '这个分类暂时没有内容',
                  message: '换一个类型或题材试试。',
                  actionLabel: '刷新',
                  onAction: _load,
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
