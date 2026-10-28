// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:window_manager/window_manager.dart';

import '../app_session.dart';
import '../models.dart';
import '../store.dart';
import '../theme.dart';
import '../widgets.dart';

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({
    super.key,
    required this.session,
    required this.detail,
    required this.index,
    this.position = 0,
  });

  final AppSession session;
  final DramaDetail detail;
  final int index;
  final double position;

  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  late final Player _player;
  late final VideoController _controller;
  late final List<StreamSubscription<dynamic>> _subscriptions;

  int _index = 0;
  String _actualQuality = '';
  bool _loading = true;
  bool _controlsVisible = true;
  bool _episodePanel = false;
  bool _fullscreen = false;
  bool _finished = false;
  String? _error;
  String _loadingMessage = '正在准备播放…';
  Duration _position = Duration.zero;
  Duration _duration = Duration.zero;
  Duration _buffer = Duration.zero;
  bool _playing = false;
  bool _buffering = false;
  int _generation = 0;
  Timer? _hideTimer;
  Timer? _saveTimer;
  DateTime _lastUpload = DateTime.fromMillisecondsSinceEpoch(0);
  bool _scrubbing = false;
  double _scrubValue = 0;

  // Danmaku state.
  List<TimedComment> _danmaku = const [];
  int _danmakuLoadedUntil = 0;
  bool _danmakuLoading = false;
  bool _danmakuEnabled = true;

  @override
  void initState() {
    super.initState();
    _index = widget.index.clamp(0, widget.detail.episodes.length - 1);
    _player = Player(configuration: const PlayerConfiguration(bufferSize: 32 * 1024 * 1024));
    _controller = VideoController(_player);
    _subscriptions = [
      _player.stream.position.listen((value) {
        if (!mounted) return;
        setState(() => _position = value);
        if (_danmakuEnabled && value.inMilliseconds > _danmakuLoadedUntil - 15000) {
          unawaited(_loadDanmaku());
        }
      }),
      _player.stream.duration.listen((value) {
        if (mounted) setState(() => _duration = value);
      }),
      _player.stream.buffer.listen((value) {
        if (mounted) setState(() => _buffer = value);
      }),
      _player.stream.playing.listen((value) {
        if (mounted) setState(() => _playing = value);
      }),
      _player.stream.buffering.listen((value) {
        if (mounted) setState(() => _buffering = value);
      }),
      _player.stream.completed.listen((value) {
        if (value) unawaited(_handleCompleted());
      }),
      _player.stream.error.listen((value) {
        if (mounted && value.isNotEmpty) setState(() => _error = value);
      }),
    ];
    _saveTimer = Timer.periodic(const Duration(seconds: 5), (_) => _saveProgress());
    WidgetsBinding.instance.addPostFrameCallback((_) => _play(_index, position: widget.position));
  }

  @override
  void dispose() {
    _saveProgress();
    _saveTimer?.cancel();
    _hideTimer?.cancel();
    for (final subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    unawaited(_player.dispose());
    if (_fullscreen) unawaited(windowManager.setFullScreen(false));
    super.dispose();
  }

  String get _episodeId =>
      _index >= 0 && _index < widget.detail.episodes.length ? widget.detail.episodes[_index] : '';

  Future<void> _play(int index, {double position = 0, bool resume = true}) async {
    if (widget.detail.episodes.isEmpty) return;
    final target = index.clamp(0, widget.detail.episodes.length - 1);
    final generation = ++_generation;
    _saveProgress();
    setState(() {
      _index = target;
      _loading = true;
      _finished = false;
      _error = null;
      _loadingMessage = '正在准备第 ${target + 1} 集…';
      _position = Duration.zero;
      _duration = Duration.zero;
      _buffer = Duration.zero;
      _danmaku = const [];
      _danmakuLoadedUntil = 0;
    });
    try {
      final result = await widget.session.api.play(
        widget.detail.episodes[target],
        quality: widget.session.store.quality,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _actualQuality = result.quality;
        _danmakuLoadedUntil = 0;
      });
      await _player.open(
        Media(
          result.url,
          start: position > 0 ? Duration(milliseconds: (position * 1000).round()) : null,
        ),
        play: resume,
      );
      if (!mounted || generation != _generation) return;
      await _player.setRate(widget.session.store.speed);
      if (mounted) setState(() => _loading = false);
      unawaited(_loadDanmaku(reset: true));
    } catch (problem) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _error = problem.toString();
      });
    }
  }

  Future<void> _loadDanmaku({bool reset = false}) async {
    if (!_danmakuEnabled || _danmakuLoading || _episodeId.isEmpty) return;
    if (!reset && _position.inMilliseconds < _danmakuLoadedUntil - 3000) return;
    _danmakuLoading = true;
    final episode = _episodeId;
    final start = reset ? 0 : _danmakuLoadedUntil;
    try {
      final page = await widget.session.api.danmaku(
        widget.detail.series.id,
        episode,
        startMs: start,
        durationMs: 60000,
      );
      if (!mounted || episode != _episodeId) return;
      setState(() {
        if (reset) {
          _danmaku = page.items;
        } else {
          _danmaku = [..._danmaku, ...page.items];
        }
        _danmakuLoadedUntil = page.nextMs > start ? page.nextMs : start + 60000;
      });
    } catch (_) {
      // Danmaku is optional; playback must keep working when the endpoint fails.
    } finally {
      _danmakuLoading = false;
    }
  }

  Future<void> _handleCompleted() async {
    if (!mounted || _finished) return;
    _finished = true;
    _saveProgress(completed: true);
    final next = _index + 1;
    if (widget.session.store.autoNext && next < widget.detail.episodes.length) {
      await _play(next);
    } else if (mounted) {
      setState(() {
        _controlsVisible = true;
        _playing = false;
      });
    }
  }

  void _saveProgress({bool completed = false}) {
    if (_episodeId.isEmpty) return;
    final seconds = _position.inMilliseconds / 1000;
    if (seconds <= 0) return;
    final total = _duration.inMilliseconds / 1000;
    final finished = completed || (total > 0 && seconds >= total - 1);
    final record = WatchRecord(
      series: widget.detail.series,
      episodeId: _episodeId,
      episodeIndex: _index,
      position: seconds,
      duration: total > 0 ? total : 0,
      completed: finished,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    widget.session.store.record(record);
    if (widget.session.loggedIn) {
      final now = DateTime.now();
      if (now.difference(_lastUpload).inSeconds >= 30 || finished) {
        _lastUpload = now;
        unawaited(
          widget.session
              .upload(record, widget.detail.episodes.length)
              .catchError((_) {}),
        );
      }
    }
  }

  void _showControls() {
    if (!_controlsVisible) setState(() => _controlsVisible = true);
    _hideTimer?.cancel();
    if (_playing) {
      _hideTimer = Timer(const Duration(seconds: 4), () {
        if (mounted && _playing && !_episodePanel) setState(() => _controlsVisible = false);
      });
    }
  }

  Future<void> _toggleFullscreen() async {
    setState(() => _fullscreen = !_fullscreen);
    await windowManager.setFullScreen(_fullscreen);
  }

  Future<void> _changeQuality(int value) async {
    if (value == widget.session.store.quality) return;
    widget.session.store.updateQuality(value);
    await _play(_index, position: _position.inMilliseconds / 1000, resume: _playing);
  }

  Future<void> _changeSpeed(double value) async {
    widget.session.store.updateSpeed(value);
    await _player.setRate(value);
  }

  @override
  Widget build(BuildContext context) {
    final episodeCount = widget.detail.episodes.length;
    return Theme(
      data: AppTheme.dark,
      child: Scaffold(
        backgroundColor: Colors.black,
        body: CallbackShortcuts(
          bindings: {
            const SingleActivator(LogicalKeyboardKey.space): () {
              _togglePlay();
              _showControls();
            },
            const SingleActivator(LogicalKeyboardKey.arrowLeft): () => _seekRelative(-10),
            const SingleActivator(LogicalKeyboardKey.arrowRight): () => _seekRelative(10),
            const SingleActivator(LogicalKeyboardKey.keyF): _toggleFullscreen,
            const SingleActivator(LogicalKeyboardKey.escape): () {
              if (_fullscreen) {
                unawaited(_toggleFullscreen());
              } else {
                Navigator.of(context).maybePop();
              }
            },
          },
          child: Focus(
            autofocus: true,
            child: MouseRegion(
              onHover: (_) => _showControls(),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _videoLayer(),
                  if (_loading) _loadingLayer(),
                  if (_error != null) _errorLayer(),
                  _controlsLayer(episodeCount),
                  if (_episodePanel) _episodePanelLayer(),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _videoLayer() => AnimatedOpacity(
    opacity: _error == null ? 1 : 0,
    duration: const Duration(milliseconds: 200),
    child: Video(
      controller: _controller,
      fit: BoxFit.contain,
      controls: (state) => _danmakuEnabled
          ? DanmakuLayer(comments: _danmaku, positionMs: _position.inMilliseconds)
          : const SizedBox.shrink(),
    ),
  );

  Widget _loadingLayer() => Container(
    color: Colors.black.withValues(alpha: 0.72),
    alignment: Alignment.center,
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const CircularProgressIndicator(),
        const SizedBox(height: 16),
        Text(_loadingMessage, style: const TextStyle(color: Colors.white70, fontSize: 13)),
      ],
    ),
  );

  Widget _errorLayer() => Container(
    color: Colors.black.withValues(alpha: 0.9),
    child: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Color(0xFFFF765F), size: 44),
            const SizedBox(height: 12),
            Text(
              _error!,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white70, fontSize: 13),
            ),
            const SizedBox(height: 20),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                FilledButton(onPressed: () => _play(_index), child: const Text('重试')),
                const SizedBox(width: 12),
                TextButton(onPressed: () => Navigator.of(context).maybePop(), child: const Text('返回')),
              ],
            ),
          ],
        ),
      ),
    ),
  );

  Widget _controlsLayer(int episodeCount) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedOpacity(
      opacity: _controlsVisible || !_playing ? 1 : 0,
      duration: const Duration(milliseconds: 180),
      child: IgnorePointer(
        ignoring: !(_controlsVisible || !_playing),
        child: Container(
          decoration: const BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0xB3000000), Color(0x00000000), Color(0xE0000000)],
              stops: [0, 0.35, 1],
            ),
          ),
          child: Column(
            children: [
              _topBar(),
              const Spacer(),
              if (_buffering && !_loading)
                const Padding(
                  padding: EdgeInsets.only(bottom: 12),
                  child: SizedBox(
                    width: 28,
                    height: 28,
                    child: CircularProgressIndicator(strokeWidth: 2.5),
                  ),
                ),
              _centerControls(episodeCount),
              const Spacer(),
              _bottomBar(colors),
            ],
          ),
        ),
      ),
    );
  }

  Widget _topBar() {
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 8, 16, 0),
        child: Row(
          children: [
            IconButton(
              tooltip: '返回',
              onPressed: () => Navigator.of(context).maybePop(),
              icon: const Icon(Icons.arrow_back, color: Colors.white),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.detail.series.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600),
                  ),
                  Text(
                    '第 ${_index + 1} 集 · ${_actualQuality.isEmpty ? '${widget.session.store.quality}P' : _actualQuality}',
                    style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                  ),
                ],
              ),
            ),
            IconButton(
              tooltip: _danmakuEnabled ? '关闭弹幕' : '开启弹幕',
              onPressed: () => setState(() => _danmakuEnabled = !_danmakuEnabled),
              icon: Icon(
                _danmakuEnabled ? Icons.subtitles : Icons.subtitles_off,
                color: Colors.white,
              ),
            ),
            IconButton(
              tooltip: '选集',
              onPressed: () => setState(() {
                _episodePanel = true;
                _controlsVisible = false;
              }),
              icon: const Icon(Icons.video_library_outlined, color: Colors.white),
            ),
          ],
        ),
      ),
    );
  }

  Widget _centerControls(int episodeCount) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        IconButton(
          tooltip: '上一集',
          onPressed: _index > 0 ? () => _play(_index - 1) : null,
          iconSize: 34,
          icon: const Icon(Icons.skip_previous, color: Colors.white),
        ),
        const SizedBox(width: 8),
        IconButton(
          tooltip: '后退 10 秒',
          onPressed: () => _seekRelative(-10),
          iconSize: 30,
          icon: const Icon(Icons.replay_10, color: Colors.white),
        ),
        const SizedBox(width: 12),
        _playPauseButton(),
        const SizedBox(width: 12),
        IconButton(
          tooltip: '前进 10 秒',
          onPressed: () => _seekRelative(10),
          iconSize: 30,
          icon: const Icon(Icons.forward_10, color: Colors.white),
        ),
        const SizedBox(width: 8),
        IconButton(
          tooltip: '下一集',
          onPressed: _index + 1 < episodeCount ? () => _play(_index + 1) : null,
          iconSize: 34,
          icon: const Icon(Icons.skip_next, color: Colors.white),
        ),
      ],
    );
  }

  Widget _playPauseButton() => SizedBox(
    width: 64,
    height: 64,
    child: Material(
      color: Colors.white.withValues(alpha: 0.14),
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: _togglePlay,
        child: Icon(
          _playing ? Icons.pause : Icons.play_arrow,
          color: Colors.white,
          size: 40,
        ),
      ),
    ),
  );

  Widget _bottomBar(ColorScheme scheme) {
    final total = _duration.inMilliseconds / 1000;
    final current = _scrubbing ? _scrubValue : _position.inMilliseconds / 1000;
    final buffered = _buffer.inMilliseconds / 1000;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: 3,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 6),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 14),
              ),
              child: Stack(
                alignment: Alignment.centerLeft,
                children: [
                  if (total > 0)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: LinearProgressIndicator(
                        value: (buffered / total).clamp(0, 1),
                        minHeight: 3,
                        backgroundColor: Colors.white24,
                        valueColor: const AlwaysStoppedAnimation(Colors.white38),
                      ),
                    ),
                  Slider(
                    value: total > 0 ? current.clamp(0, total) : 0,
                    max: total > 0 ? total : 1,
                    onChangeStart: (value) => setState(() {
                      _scrubbing = true;
                      _scrubValue = value;
                    }),
                    onChanged: (value) => setState(() => _scrubValue = value),
                    onChangeEnd: (value) {
                      setState(() => _scrubbing = false);
                      unawaited(_player.seek(Duration(milliseconds: (value * 1000).round())));
                      _showControls();
                    },
                  ),
                ],
              ),
            ),
            Row(
              children: [
                Text(
                  '${formatClock(current)} / ${formatClock(total)}',
                  style: TextStyle(color: scheme.onSurfaceVariant, fontSize: 12),
                ),
                const SizedBox(width: 16),
                _speedButton(),
                const SizedBox(width: 8),
                _qualityButton(),
                const Spacer(),
                IconButton(
                  tooltip: '音量',
                  onPressed: () async {
                    final volume = _player.state.volume;
                    await _player.setVolume(volume > 0 ? 0 : 100);
                  },
                  icon: const Icon(Icons.volume_up, color: Colors.white, size: 20),
                ),
                IconButton(
                  tooltip: _fullscreen ? '退出全屏' : '全屏',
                  onPressed: _toggleFullscreen,
                  icon: Icon(
                    _fullscreen ? Icons.fullscreen_exit : Icons.fullscreen,
                    color: Colors.white,
                    size: 22,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _speedButton() => PopupMenuButton<double>(
    tooltip: '倍速',
    color: const Color(0xFF1E2026),
    initialValue: widget.session.store.speed,
    onSelected: (value) => unawaited(_changeSpeed(value)),
    itemBuilder: (context) => [
      for (final value in speeds)
        PopupMenuItem(
          value: value,
          child: Text(
            '${value == value.roundToDouble() ? value.toInt() : value}x',
            style: const TextStyle(color: Colors.white),
          ),
        ),
    ],
    child: Text(
      '${widget.session.store.speed == widget.session.store.speed.roundToDouble() ? widget.session.store.speed.toInt() : widget.session.store.speed}x',
      style: const TextStyle(color: Colors.white, fontSize: 13),
    ),
  );

  Widget _qualityButton() => PopupMenuButton<int>(
    tooltip: '清晰度',
    color: const Color(0xFF1E2026),
    initialValue: widget.session.store.quality,
    onSelected: (value) => unawaited(_changeQuality(value)),
    itemBuilder: (context) => [
      for (final value in const [480, 720, 1080])
        PopupMenuItem(
          value: value,
          child: Text('$value P', style: const TextStyle(color: Colors.white)),
        ),
    ],
    child: Text(
      _actualQuality.isEmpty ? '${widget.session.store.quality}P' : _actualQuality,
      style: const TextStyle(color: Colors.white, fontSize: 13),
    ),
  );

  Widget _episodePanelLayer() {
    final scheme = Theme.of(context).colorScheme;
    return Positioned.fill(
      child: GestureDetector(
        onTap: () => setState(() {
          _episodePanel = false;
          _showControls();
        }),
        child: Container(
          color: Colors.black54,
          child: Align(
            alignment: Alignment.centerRight,
            child: GestureDetector(
              onTap: () {},
              child: Container(
                width: 360,
                height: double.infinity,
                color: const Color(0xFF101114),
                child: SafeArea(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
                        child: Row(
                          children: [
                            const Text(
                              '选集',
                              style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600),
                            ),
                            const Spacer(),
                            IconButton(
                              onPressed: () => setState(() {
                                _episodePanel = false;
                                _showControls();
                              }),
                              icon: Icon(Icons.close, color: scheme.onSurfaceVariant),
                            ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: GridView.builder(
                          padding: const EdgeInsets.all(16),
                          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 4,
                            mainAxisSpacing: 10,
                            crossAxisSpacing: 10,
                            childAspectRatio: 1.5,
                          ),
                          itemCount: widget.detail.episodes.length,
                          itemBuilder: (context, index) {
                            final selected = index == _index;
                            return InkWell(
                              onTap: () {
                                setState(() => _episodePanel = false);
                                unawaited(_play(index));
                              },
                              borderRadius: BorderRadius.circular(10),
                              child: Container(
                                alignment: Alignment.center,
                                decoration: BoxDecoration(
                                  color: selected ? scheme.primary : scheme.surfaceContainerHigh,
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                child: Text(
                                  '${index + 1}',
                                  style: TextStyle(
                                    color: selected ? scheme.onPrimary : Colors.white,
                                    fontSize: 13,
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _togglePlay() {
    if (_loading) return;
    if (_playing) {
      unawaited(_player.pause());
      _saveProgress();
    } else {
      if (_finished && _position >= _duration - const Duration(seconds: 1)) {
        unawaited(_play(_index));
      } else {
        unawaited(_player.play());
      }
    }
  }

  void _seekRelative(int seconds) {
    if (_duration == Duration.zero) return;
    final target = _position + Duration(seconds: seconds);
    final clamped = target < Duration.zero
        ? Duration.zero
        : target > _duration
        ? _duration
        : target;
    unawaited(_player.seek(clamped));
    setState(() => _position = clamped);
    _showControls();
  }
}

/// Two-lane danmaku layer drawn above the video surface. Entries travel right to left over
/// 8 seconds starting at their timestamp.
class DanmakuLayer extends StatelessWidget {
  const DanmakuLayer({super.key, required this.comments, required this.positionMs});

  final List<TimedComment> comments;
  final int positionMs;

  static const _travelMs = 8000;

  @override
  Widget build(BuildContext context) {
    if (comments.isEmpty) return const SizedBox.shrink();
    return IgnorePointer(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final width = constraints.maxWidth;
          const lanes = 2;
          final laneHeight = 28.0;
          final visible = <Widget>[];
          for (var index = 0; index < comments.length; index++) {
            final item = comments[index];
            final elapsed = positionMs - item.timeMs;
            if (elapsed < 0 || elapsed > _travelMs) continue;
            final progress = elapsed / _travelMs;
            final x = width - (width + 320) * progress;
            final lane = index % lanes;
            visible.add(
              Positioned(
                left: x,
                top: 12 + lane * laneHeight,
                child: Text(
                  item.text,
                  maxLines: 1,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    shadows: [Shadow(blurRadius: 4, color: Colors.black87)],
                  ),
                ),
              ),
            );
          }
          return ClipRect(child: Stack(children: visible));
        },
      ),
    );
  }
}
