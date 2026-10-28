// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';

import '../api.dart';
import '../app_session.dart';
import '../models.dart';
import '../widgets.dart';
import 'player_screen.dart';

class DetailScreen extends StatefulWidget {
  const DetailScreen({
    super.key,
    required this.session,
    required this.series,
  });

  final AppSession session;
  final Series series;

  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  DramaDetail? _detail;
  List<RelatedEntry> _related = const [];
  String? _error;
  String _relatedMessage = '';
  bool _loading = true;
  bool _descriptionExpanded = false;
  int? _playingIndex;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
      _relatedMessage = '正在读取相关作品…';
    });
    try {
      final detail = await widget.session.api.detail(widget.series.id);
      if (!mounted) return;
      setState(() {
        _detail = detail;
        _loading = false;
        _playingIndex = _resumeIndex(detail);
      });
      unawaited(_loadRelated());
    } catch (problem) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = problem.toString();
      });
    }
  }

  Future<void> _loadRelated() async {
    try {
      final entries = await widget.session.api.related(widget.series.id);
      if (!mounted) return;
      setState(() {
        _related = entries;
        _relatedMessage = entries.isEmpty ? '暂无相关作品' : '';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _relatedMessage = '相关作品暂时无法加载');
    }
  }

  int _resumeIndex(DramaDetail detail) {
    final saved = widget.session.store.recordFor(detail.series.id);
    if (saved == null) return 0;
    final index = detail.episodes.indexOf(saved.episodeId);
    if (index < 0) return 0;
    if (saved.completed && index + 1 < detail.episodes.length) return index + 1;
    return index;
  }

  double _resumePosition(DramaDetail detail, int index) {
    final saved = widget.session.store.recordFor(detail.series.id);
    if (saved == null || saved.episodeId != detail.episodes[index] || saved.completed) return 0;
    return saved.position;
  }

  Future<void> _openPlayer(int index) async {
    final detail = _detail;
    if (detail == null || detail.episodes.isEmpty) return;
    final position = _resumePosition(detail, index);
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => PlayerScreen(
          session: widget.session,
          detail: detail,
          index: index,
          position: position,
        ),
      ),
    );
    if (mounted) setState(() => _playingIndex = _resumeIndex(detail));
  }

  @override
  Widget build(BuildContext context) {
    final detail = _detail;
    return Scaffold(
      appBar: AppBar(
        title: Text(detail?.series.title ?? widget.series.title),
        actions: [
          IconButton(
            tooltip: '评论与剧评',
            onPressed: detail == null ? null : _openComments,
            icon: const Icon(Icons.forum_outlined),
          ),
          IconButton(
            tooltip: widget.session.store.isFavorite(widget.series.id) ? '取消收藏' : '加入收藏',
            onPressed: () {
              widget.session.store.toggleFavorite(detail?.series ?? widget.series);
              setState(() {});
            },
            icon: Icon(
              widget.session.store.isFavorite(widget.series.id) ? Icons.favorite : Icons.favorite_border,
            ),
          ),
          IconButton(tooltip: '刷新', onPressed: _loading ? null : _load, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1200),
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                ? StatusPanel(
                    icon: Icons.cloud_off,
                    title: '剧集信息加载失败',
                    message: _error!,
                    actionLabel: '重试',
                    onAction: _load,
                  )
                : _content(detail!),
          ),
        ),
      ),
    );
  }

  Widget _content(DramaDetail detail) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 960;
        if (wide) {
          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(
                width: (constraints.maxWidth * 0.32).clamp(280, 360),
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(24, 20, 16, 32),
                  child: _overview(detail),
                ),
              ),
              const VerticalDivider(),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(20, 20, 24, 32),
                  children: [
                    _episodes(detail),
                    const SizedBox(height: 28),
                    _relatedRail(),
                  ],
                ),
              ),
            ],
          );
        }
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
          children: [
            _overview(detail),
            const SizedBox(height: 20),
            _episodes(detail),
            const SizedBox(height: 24),
            _relatedRail(),
          ],
        );
      },
    );
  }

  Widget _overview(DramaDetail detail) {
    final scheme = Theme.of(context).colorScheme;
    final series = detail.series;
    final saved = widget.session.store.recordFor(series.id);
    final resumeLabel = saved == null ? '从第 1 集播放' : '继续第 ${_resumeIndex(detail) + 1} 集';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 108, child: DramaCover(series: series, badge: series.badge)),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    series.title,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 8),
                  if (series.tags.isNotEmpty)
                    Text(
                      series.tags,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                    ),
                  const SizedBox(height: 8),
                  Text(
                    '共 ${detail.episodes.length} 集',
                    style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        Row(
          children: [
            Expanded(
              child: FilledButton.icon(
                onPressed: detail.episodes.isEmpty
                    ? null
                    : () => _openPlayer(_resumeIndex(detail)),
                icon: const Icon(Icons.play_arrow, size: 20),
                label: Text(resumeLabel),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () {
                  widget.session.store.toggleFavorite(series);
                  setState(() {});
                },
                icon: Icon(
                  widget.session.store.isFavorite(series.id) ? Icons.favorite : Icons.favorite_border,
                  size: 18,
                ),
                label: Text(widget.session.store.isFavorite(series.id) ? '已收藏' : '本机收藏'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _openComments,
                icon: const Icon(Icons.forum_outlined, size: 18),
                label: const Text('评论'),
              ),
            ),
          ],
        ),
        if (series.description.isNotEmpty) ...[
          const SizedBox(height: 20),
          Text('简介', style: TextStyle(fontWeight: FontWeight.w600, color: scheme.onSurface)),
          const SizedBox(height: 6),
          Text(
            series.description,
            maxLines: _descriptionExpanded ? null : 5,
            overflow: _descriptionExpanded ? null : TextOverflow.ellipsis,
            style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13, height: 1.6),
          ),
          if (series.description.length > 90)
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(
                onPressed: () => setState(() => _descriptionExpanded = !_descriptionExpanded),
                child: Text(_descriptionExpanded ? '收起' : '展开全部'),
              ),
            ),
        ],
      ],
    );
  }

  Widget _episodes(DramaDetail detail) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionTitle(
          '选集',
          trailing: Text(
            '已更新 ${detail.episodes.length} 集',
            style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
          ),
        ),
        _episodeGrid(detail),
      ],
    );
  }

  Widget _episodeGrid(DramaDetail detail) {
    final scheme = Theme.of(context).colorScheme;
    final grid = GridView.builder(
      // Long-running shows can expose hundreds of episodes. Keep the grid bounded and
      // lazily built in that case, otherwise let it size itself inside the page scroll.
      shrinkWrap: detail.episodes.length <= 24,
      physics: detail.episodes.length <= 24
          ? const NeverScrollableScrollPhysics()
          : const ClampingScrollPhysics(),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 84,
        mainAxisSpacing: 10,
        crossAxisSpacing: 10,
        childAspectRatio: 1.55,
      ),
      itemCount: detail.episodes.length,
      itemBuilder: (context, index) {
        final selected = index == _playingIndex;
        return InkWell(
          onTap: () => _openPlayer(index),
          borderRadius: BorderRadius.circular(10),
          child: Container(
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: selected ? scheme.primary : scheme.surfaceContainer,
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              '${index + 1}',
              style: TextStyle(
                color: selected ? scheme.onPrimary : scheme.onSurface,
                fontSize: 13,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w400,
              ),
            ),
          ),
        );
      },
    );
    if (detail.episodes.length <= 24) return grid;
    return SizedBox(height: 320, child: grid);
  }

  Widget _relatedRail() {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SectionTitle('相关作品'),
        if (_relatedMessage.isNotEmpty)
          Text(_relatedMessage, style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
        SizedBox(
          height: 190,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            itemCount: _related.length,
            separatorBuilder: (_, _) => const SizedBox(width: 12),
            itemBuilder: (context, index) {
              final entry = _related[index];
              return SizedBox(
                width: 110,
                child: InkWell(
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute<void>(
                      builder: (_) => DetailScreen(
                        session: widget.session,
                        series: entry.series,
                      ),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      DramaCover(series: entry.series, radius: 10),
                      const SizedBox(height: 6),
                      Text(
                        entry.series.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 12),
                      ),
                      Text(
                        entry.upcoming ? '待上线' : entry.season,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }

  Future<void> _openComments() async {
    final detail = _detail;
    if (detail == null) return;
    final episodeId = detail.episodes.isEmpty ? '' : detail.episodes[_playingIndex ?? 0];
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      backgroundColor: Theme.of(context).colorScheme.surface,
      builder: (_) => FractionallySizedBox(
        heightFactor: 0.8,
        child: CommentsSheet(
          api: widget.session.api,
          seriesId: detail.series.id,
          episodeId: episodeId,
        ),
      ),
    );
  }
}

class CommentsSheet extends StatefulWidget {
  const CommentsSheet({
    super.key,
    required this.api,
    required this.seriesId,
    required this.episodeId,
  });

  final Api api;
  final String seriesId;
  final String episodeId;

  @override
  State<CommentsSheet> createState() => _CommentsSheetState();
}

class _CommentsSheetState extends State<CommentsSheet> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 2, vsync: this);
  Social? _social;
  final List<Comment> _comments = [];
  String _cursor = '';
  bool _hasMore = false;
  bool _loading = true;
  bool _loadingMore = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final social = await widget.api.comments(widget.seriesId, widget.episodeId);
      final page = await widget.api.commentPage(widget.seriesId, widget.episodeId, '');
      if (!mounted) return;
      setState(() {
        _social = social;
        _comments
          ..clear()
          ..addAll(page.items);
        _cursor = page.nextCursor;
        _hasMore = page.hasMore;
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
      final page = await widget.api.commentPage(widget.seriesId, widget.episodeId, _cursor);
      if (!mounted) return;
      setState(() {
        _comments.addAll(page.items);
        _cursor = page.nextCursor;
        _hasMore = page.hasMore;
      });
    } catch (_) {
      // Keep the loaded page; the user can retry.
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Column(
      children: [
        TabBar(
          controller: _tabs,
          tabs: const [Tab(text: '本集评论'), Tab(text: '整剧剧评')],
          labelColor: scheme.primary,
          indicatorColor: scheme.primary,
        ),
        Expanded(
          child: _loading
              ? const Center(child: CircularProgressIndicator())
              : _error != null
              ? StatusPanel(
                  icon: Icons.forum_outlined,
                  title: '评论加载失败',
                  message: _error!,
                  actionLabel: '重试',
                  onAction: _load,
                )
              : TabBarView(
                  controller: _tabs,
                  children: [_commentList(), _reviews()],
                ),
        ),
      ],
    );
  }

  Widget _commentList() {
    final scheme = Theme.of(context).colorScheme;
    if (_comments.isEmpty) {
      return const StatusPanel(icon: Icons.chat_bubble_outline, title: '暂时没有评论');
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      itemCount: _comments.length + (_hasMore ? 1 : 0),
      itemBuilder: (context, index) {
        if (index == _comments.length) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Center(
              child: _loadingMore
                  ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                  : TextButton(onPressed: _loadMore, child: const Text('加载更多')),
            ),
          );
        }
        final comment = _comments[index];
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              CircleAvatar(
                radius: 16,
                backgroundColor: scheme.surfaceContainerHigh,
                child: Text(
                  comment.author.isEmpty ? '?' : comment.author[0],
                  style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(
                          comment.author,
                          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                        ),
                        const Spacer(),
                        if (comment.likes > 0)
                          Text(
                            '${comment.likes} 赞',
                            style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
                          ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(comment.content, style: const TextStyle(fontSize: 13, height: 1.5)),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _reviews() {
    final social = _social;
    final scheme = Theme.of(context).colorScheme;
    if (social == null || social.reviews.items.isEmpty) {
      return const StatusPanel(icon: Icons.reviews_outlined, title: '暂时没有剧评');
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        Text(
          '评分 ${social.rating.toStringAsFixed(1)} · ${social.ratingCount} 人评价',
          style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
        ),
        const SizedBox(height: 12),
        for (final review in social.reviews.items)
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  review.author,
                  style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
                ),
                const SizedBox(height: 4),
                Text(review.content, style: const TextStyle(fontSize: 13, height: 1.6)),
              ],
            ),
          ),
      ],
    );
  }
}
