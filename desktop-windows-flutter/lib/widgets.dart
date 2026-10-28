// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';

import 'models.dart';
import 'theme.dart';

String formatClock(double seconds) {
  if (!seconds.isFinite || seconds < 0) return '00:00';
  final total = seconds.toInt();
  final minutes = (total ~/ 60).toString().padLeft(2, '0');
  return '$minutes:${(total % 60).toString().padLeft(2, '0')}';
}

/// The feed serves HEIC renditions; the public image host exposes the same object as a
/// JPEG through this template, which Flutter can decode without extra codecs.
String? jpegFallback(String url) {
  final match = RegExp(r'^https://(p\d+)[a-z-]*\.fqnovelpic\.com/novel-pic/([0-9a-fA-F]+)~.*$')
      .firstMatch(url);
  if (match == null) return null;
  return 'https://${match.group(1)}-novel.byteimg.com/novel-pic/'
      '${match.group(2)}~tplv-shrink:640:0.image';
}

class DramaCover extends StatefulWidget {
  const DramaCover({
    super.key,
    required this.series,
    this.radius = 14,
    this.badge,
    this.episodeCount,
  });

  final Series series;
  final double radius;
  final String? badge;
  final String? episodeCount;

  @override
  State<DramaCover> createState() => _DramaCoverState();
}

class _DramaCoverState extends State<DramaCover> {
  String? _url;

  @override
  void initState() {
    super.initState();
    _url = _firstCandidate();
  }

  @override
  void didUpdateWidget(covariant DramaCover oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.series.cover != widget.series.cover) {
      _url = _firstCandidate();
    }
  }

  String? _firstCandidate() {
    final cover = widget.series.cover;
    if (cover.isEmpty) return null;
    if (cover.toLowerCase().contains('.heic')) return jpegFallback(cover) ?? cover;
    return cover;
  }

  void _fallback() {
    final cover = widget.series.cover;
    final fallback = jpegFallback(cover);
    if (_url != cover && fallback != null && _url != cover) {
      // A HEIC/JPEG candidate failed; try the original URL once.
      if (mounted) setState(() => _url = cover);
      return;
    }
    if (mounted) setState(() => _url = null);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ClipRRect(
      borderRadius: BorderRadius.circular(widget.radius),
      child: AspectRatio(
        aspectRatio: 2 / 3,
        child: Stack(
          fit: StackFit.expand,
          children: [
            DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [scheme.surfaceContainerHighest, scheme.surfaceContainer],
                ),
              ),
              child: Icon(Icons.movie_outlined, color: scheme.onSurfaceVariant, size: 28),
            ),
            if (_url != null)
              Image.network(
                _url!,
                fit: BoxFit.cover,
                gaplessPlayback: true,
                errorBuilder: (_, _, _) {
                  WidgetsBinding.instance.addPostFrameCallback((_) => _fallback());
                  return const SizedBox.shrink();
                },
              ),
            const DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.center,
                  end: Alignment.bottomCenter,
                  colors: [Colors.transparent, Color(0xC7000000)],
                ),
              ),
            ),
            if (widget.episodeCount != null)
              Positioned(
                left: 8,
                bottom: 8,
                child: Text(
                  widget.episodeCount!,
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
              ),
            if (widget.badge != null && widget.badge!.isNotEmpty)
              Positioned(
                top: 0,
                right: 0,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: const BoxDecoration(
                    color: AppTheme.accentGold,
                    borderRadius: BorderRadius.only(
                      topRight: Radius.circular(14),
                      bottomLeft: Radius.circular(6),
                    ),
                  ),
                  child: Text(
                    widget.badge!,
                    style: const TextStyle(
                      color: AppTheme.onAccentGold,
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class DramaTile extends StatelessWidget {
  const DramaTile({
    super.key,
    required this.series,
    this.subtitle = '',
    this.trailing,
    this.onTap,
    this.onSecondaryTap,
  });

  final Series series;
  final String subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final VoidCallback? onSecondaryTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      onSecondaryTap: onSecondaryTap,
      borderRadius: BorderRadius.circular(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Stack(
            children: [
              DramaCover(series: series, badge: series.badge),
              if (trailing != null) Positioned(top: 4, left: 4, child: trailing!),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            series.title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 14,
              height: 1.25,
              fontWeight: FontWeight.w600,
              color: scheme.onSurface,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            subtitle.isNotEmpty ? subtitle : series.tags,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class StatusPanel extends StatelessWidget {
  const StatusPanel({
    super.key,
    required this.icon,
    required this.title,
    this.message = '',
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: scheme.primary),
            const SizedBox(height: 16),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            if (message.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(
                message,
                textAlign: TextAlign.center,
                style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 13),
              ),
            ],
            if (onAction != null && actionLabel != null) ...[
              const SizedBox(height: 20),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}

class RefreshAction extends StatelessWidget {
  const RefreshAction({super.key, required this.loading, required this.onPressed});

  final bool loading;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: '刷新',
    onPressed: loading ? null : onPressed,
    icon: loading
        ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
        : const Icon(Icons.refresh),
  );
}

class ChipRail extends StatelessWidget {
  const ChipRail({
    super.key,
    required this.options,
    required this.selected,
    required this.onSelect,
    this.trailing,
  });

  final List<(String, String)> options;
  final String selected;
  final ValueChanged<String> onSelect;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 44,
      child: Row(
        children: [
          Expanded(
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: options.length,
              separatorBuilder: (_, _) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final (value, label) = options[index];
                return ChoiceChip(
                  label: Text(label),
                  selected: selected == value,
                  onSelected: (_) => onSelect(value),
                );
              },
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ],
      ),
    );
  }
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.title, {super.key, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Row(
        children: [
          Container(
            width: 3,
            height: 16,
            decoration: BoxDecoration(
              color: scheme.primary,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(width: 8),
          Text(title, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
          const Spacer(),
          ?trailing,
        ],
      ),
    );
  }
}

/// Responsive grid metrics used by every catalog surface.
SliverGridDelegate dramaGridDelegate(BuildContext context, double width) {
  final columns = width < 600 ? 3 : (width / 200).floor().clamp(4, 9);
  return SliverGridDelegateWithFixedCrossAxisCount(
    crossAxisCount: columns,
    mainAxisSpacing: 20,
    crossAxisSpacing: 16,
    childAspectRatio: 2 / 3.62,
  );
}

/// Grid of catalog cards with an optional "load more" footer, shared by home, discover and
/// search.
class CatalogGrid extends StatelessWidget {
  const CatalogGrid({
    super.key,
    required this.items,
    required this.onTap,
    this.hasMore = false,
    this.loadingMore = false,
    this.onLoadMore,
  });

  final List<Series> items;
  final ValueChanged<Series> onTap;
  final bool hasMore;
  final bool loadingMore;
  final VoidCallback? onLoadMore;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => CustomScrollView(
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
            sliver: SliverGrid.builder(
              gridDelegate: dramaGridDelegate(context, constraints.maxWidth),
              itemCount: items.length,
              itemBuilder: (context, index) => DramaTile(
                series: items[index],
                onTap: () => onTap(items[index]),
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 20),
              child: Center(
                child: loadingMore
                    ? const SizedBox(
                        width: 22,
                        height: 22,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : hasMore && onLoadMore != null
                    ? TextButton(onPressed: onLoadMore, child: const Text('加载更多'))
                    : const SizedBox(height: 8),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class RankBadge extends StatelessWidget {
  const RankBadge({super.key, required this.rank});

  final int rank;

  @override
  Widget build(BuildContext context) {
    final top = rank <= 3;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: top ? AppTheme.accentGold : const Color(0xCC000000),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        'NO.$rank',
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: top ? AppTheme.onAccentGold : Colors.white,
        ),
      ),
    );
  }
}

class ErrorBanner extends StatelessWidget {
  const ErrorBanner({super.key, required this.message, this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: scheme.errorContainer.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, size: 18, color: scheme.error),
          const SizedBox(width: 8),
          Expanded(child: Text(message, style: TextStyle(fontSize: 13, color: scheme.onSurface))),
          if (onRetry != null) TextButton(onPressed: onRetry, child: const Text('重试')),
        ],
      ),
    );
  }
}
