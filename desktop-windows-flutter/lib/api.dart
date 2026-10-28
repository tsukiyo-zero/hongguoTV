// SPDX-License-Identifier: GPL-3.0-only
import 'bridge.dart';
import 'models.dart';

/// Typed façade over the loopback bridge, mirroring the endpoint table in
/// `desktop-mac/backend/src/main/kotlin/com/hongguotv/desktop/Main.kt`.
class Api {
  Api(this._bridge);

  final Bridge _bridge;

  Future<CatalogPage> feed({String category = 'ALL', int page = 1, bool refresh = true}) async {
    final value = await _bridge.get('feed', {
      'category': category,
      'page': '$page',
      'refresh': '$refresh',
    });
    return _catalog(value);
  }

  Future<CatalogPage> search(String query, {int page = 1}) async {
    final value = await _bridge.get('search', {'q': query, 'page': '$page'});
    return _catalog(value);
  }

  Future<CatalogPage> catalog(
    String category, {
    String topic = '',
    int page = 1,
  }) async {
    final value = await _bridge.get('catalog', {
      'category': category,
      'topic': topic,
      'page': '$page',
    });
    return _catalog(value);
  }

  Future<CatalogPage> rank(String board, {String topic = ''}) async {
    final value = await _bridge.get('rank', {'board': board, 'topic': topic});
    final positions = <String, RankPosition>{};
    final raw = value['positions'];
    if (raw is Map) {
      raw.forEach((key, item) {
        if (item is! Map) return;
        positions['$key'] = RankPosition(
          rank: (item['rank'] as num?)?.toInt(),
          heat: item['heat'] as String? ?? '',
        );
      });
    }
    return CatalogPage(
      items: seriesList(value['items']),
      hasMore: value['hasMore'] as bool? ?? false,
      topics: topicList(value['topics']),
      positions: positions,
    );
  }

  Future<DramaDetail> detail(String id) async =>
      detailFromJson(await _bridge.get('detail', {'id': id}));

  Future<List<RelatedEntry>> related(String id) async {
    final value = await _bridge.get('related', {'id': id});
    final entries = <RelatedEntry>[];
    for (final row in (value['items'] as List?) ?? const []) {
      if (row is! Map) continue;
      final series = row['series'];
      if (series is! Map) continue;
      entries.add(
        RelatedEntry(
          series: Series.fromJson(Map<String, dynamic>.from(series)),
          season: row['season'] as String? ?? '',
          upcoming: row['upcoming'] as bool? ?? false,
        ),
      );
    }
    return entries;
  }

  Future<PlayResult> play(String episodeId, {int quality = 1080}) async {
    final value = await _bridge.get('play', {'id': episodeId, 'quality': '$quality'});
    final url = value['url'] as String? ?? '';
    if (!url.startsWith('http://127.0.0.1:')) {
      throw BridgeException('播放地址无效，请重试。');
    }
    return PlayResult(url: url, quality: value['quality'] as String? ?? '');
  }

  Future<Social> comments(String series, String episode) async =>
      socialFromJson(await _bridge.get('comments', {'series': series, 'episode': episode}));

  Future<CommentPage> commentPage(String series, String episode, String cursor) async =>
      commentPageFromJson(
        await _bridge.get('comment-page', {
          'series': series,
          'episode': episode,
          'cursor': cursor,
        }),
      );

  Future<TimedPage> danmaku(
    String series,
    String episode, {
    required int startMs,
    required int durationMs,
  }) async => timedPageFromJson(
    await _bridge.get('danmaku', {
      'series': series,
      'episode': episode,
      'start': '$startMs',
      'duration': '$durationMs',
    }),
  );

  Future<AccountInfo> accountStatus() async => AccountInfo.fromJson(await _bridge.get('account/status'));

  Future<AccountInfo> restoreAccount(String credentials) async =>
      AccountInfo.fromJson(await _bridge.post('account/restore', {'credentials': credentials}));

  Future<Map<String, dynamic>> accountQr() => _bridge.post('account/qr');

  Future<AccountInfo> poll(String loginId) async =>
      AccountInfo.fromJson(await _bridge.get('account/poll', {'loginID': loginId}));

  Future<void> cancelLogin(String loginId) async =>
      _bridge.post('account/cancel', {'loginID': loginId});

  Future<void> logout() async => _bridge.post('account/logout');

  Future<List<CloudEntry>> cloud(String type, {bool online = true}) async {
    final path = switch (type) {
      '账户历史' => 'account/history',
      '账户收藏' => 'account/favorites',
      _ => 'account/reservations',
    };
    final value = await _bridge.get(path, path == 'account/reservations' ? {'online': '$online'} : null);
    return cloudEntriesFromJson(value);
  }

  Future<void> setFavorite(String id, bool add, String accountId) async =>
      _bridge.post('account/favorite', {'id': id, 'add': add, 'accountID': accountId});

  Future<void> reserve(String id, String accountId) async =>
      _bridge.post('account/reserve', {'id': id, 'accountID': accountId});

  Future<void> upload({
    required Series series,
    required String episodeId,
    required int episodeIndex,
    required double position,
    required double duration,
    required int episodeCount,
    required String accountId,
  }) => _bridge.post('account/upload', {
    'series': series.toJson(),
    'episodeID': episodeId,
    'episodeIndex': episodeIndex,
    'position': position * 1000,
    'duration': duration * 1000,
    'episodeCount': episodeCount,
    'accountID': accountId,
  });

  static CatalogPage _catalog(Map<String, dynamic> value) => CatalogPage(
    items: seriesList(value['items']),
    hasMore: value['hasMore'] as bool? ?? false,
    topics: topicList(value['topics']),
  );
}
