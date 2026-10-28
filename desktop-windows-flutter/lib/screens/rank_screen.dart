// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';

import '../app_session.dart';
import '../models.dart';
import '../widgets.dart';
import 'detail_screen.dart';

/// Rank boards (综合 / 漫剧 / 真人剧 / AI 剧) with the same topic filter as the catalog.
class RankScreen extends StatefulWidget {
  const RankScreen({super.key, required this.session});

  final AppSession session;

  @override
  State<RankScreen> createState() => _RankScreenState();
}

class _RankScreenState extends State<RankScreen> {
  static const _boards = [
    ('ALL', '综合'),
    ('COMIC', '漫剧'),
    ('REAL', '真人剧'),
    ('AI', 'AI 剧'),
  ];

  final List<Series> _items = [];
  Map<String, RankPosition> _positions = const {};
  List<Topic> _topics = const [];
  String _board = 'ALL';
  String _topic = '';
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await widget.session.api.rank(_board, topic: _topic);
      if (!mounted) return;
      setState(() {
        _items
          ..clear()
          ..addAll(result.items);
        _positions = result.positions;
        if (result.topics.isNotEmpty) _topics = result.topics;
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

  void _selectBoard(String value) {
    if (_board == value) return;
    setState(() {
      _board = value;
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
                  '热播榜单',
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
          child: ChipRail(options: _boards, selected: _board, onSelect: _selectBoard),
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
                  icon: Icons.bar_chart,
                  title: '榜单加载失败',
                  message: _error!,
                  actionLabel: '重试',
                  onAction: _load,
                )
              : _items.isEmpty
              ? StatusPanel(
                  icon: Icons.bar_chart,
                  title: '这个榜单暂时没有内容',
                  message: '换一个榜单或题材试试。',
                  actionLabel: '刷新',
                  onAction: _load,
                )
              : _rankGrid(),
        ),
      ],
    );
  }

  Widget _rankGrid() {
    return LayoutBuilder(
      builder: (context, constraints) => GridView.builder(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        gridDelegate: dramaGridDelegate(context, constraints.maxWidth),
        itemCount: _items.length,
        itemBuilder: (context, index) {
          final series = _items[index];
          final position = _positions[series.id];
          final rank = position?.rank;
          return DramaTile(
            series: series,
            subtitle: (position?.heat.isNotEmpty ?? false) ? position!.heat : series.tags,
            trailing: rank == null ? null : RankBadge(rank: rank),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => DetailScreen(session: widget.session, series: series),
              ),
            ),
          );
        },
      ),
    );
  }
}
