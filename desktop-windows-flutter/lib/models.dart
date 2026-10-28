// SPDX-License-Identifier: GPL-3.0-only
/// Wire models of the loopback bridge shared with the Mac client.
class Series {
  const Series({
    required this.id,
    required this.title,
    this.cover = '',
    this.description = '',
    this.badge = '',
    this.tags = '',
  });

  final String id;
  final String title;
  final String cover;
  final String description;
  final String badge;
  final String tags;

  factory Series.fromJson(Map<String, dynamic> json) => Series(
    id: json['id'] as String? ?? '',
    title: json['title'] as String? ?? '',
    cover: json['cover'] as String? ?? '',
    description: json['description'] as String? ?? '',
    badge: json['badge'] as String? ?? '',
    tags: json['tags'] as String? ?? '',
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'title': title,
    'cover': cover,
    'description': description,
    'badge': badge,
    'tags': tags,
  };
}

class Topic {
  const Topic(this.id, this.label);
  final String id;
  final String label;
}

class RankPosition {
  const RankPosition({this.rank, this.heat = ''});
  final int? rank;
  final String heat;
}

class RelatedEntry {
  const RelatedEntry({required this.series, this.season = '', this.upcoming = false});
  final Series series;
  final String season;
  final bool upcoming;
}

class DramaDetail {
  const DramaDetail({required this.series, required this.episodes});
  final Series series;
  final List<String> episodes;
}

class PlayResult {
  const PlayResult({required this.url, required this.quality});
  final String url;
  final String quality;
}

class CatalogPage {
  const CatalogPage({
    required this.items,
    required this.hasMore,
    this.topics = const [],
    this.positions = const {},
  });
  final List<Series> items;
  final bool hasMore;
  final List<Topic> topics;
  final Map<String, RankPosition> positions;
}

class Comment {
  const Comment({
    required this.id,
    required this.author,
    this.avatar = '',
    this.content = '',
    this.created = 0,
    this.likes = 0,
    this.rating = 0,
  });
  final String id;
  final String author;
  final String avatar;
  final String content;
  final int created;
  final int likes;
  final double rating;
}

class CommentList {
  const CommentList({this.total = 0, this.items = const []});
  final int total;
  final List<Comment> items;
}

class Social {
  const Social({
    this.seriesId = '',
    this.episodeId = '',
    this.reviews = const CommentList(),
    this.comments = const CommentList(),
    this.rating = 0,
    this.ratingCount = 0,
    this.sourceUrl = '',
    this.limited = true,
  });
  final String seriesId;
  final String episodeId;
  final CommentList reviews;
  final CommentList comments;
  final double rating;
  final int ratingCount;
  final String sourceUrl;
  final bool limited;
}

class CommentPage {
  const CommentPage({
    this.total = 0,
    this.items = const [],
    this.nextCursor = '',
    this.hasMore = false,
  });
  final int total;
  final List<Comment> items;
  final String nextCursor;
  final bool hasMore;
}

class AccountInfo {
  const AccountInfo({this.loggedIn = false, this.id, this.nickname, this.credentials});
  final bool loggedIn;
  final String? id;
  final String? nickname;
  final Map<String, dynamic>? credentials;

  factory AccountInfo.fromJson(Map<String, dynamic> json) {
    final credentials = json['credentials'];
    return AccountInfo(
      loggedIn: json['loggedIn'] as bool? ?? false,
      id: (json['id'] as String?)?.trim().isNotEmpty == true ? json['id'] as String : null,
      nickname:
          (json['nickname'] as String?)?.trim().isNotEmpty == true ? json['nickname'] as String : null,
      credentials: credentials is Map ? Map<String, dynamic>.from(credentials) : null,
    );
  }
}

class CloudEntry {
  const CloudEntry({
    required this.series,
    this.episodeId,
    this.episodeIndex,
    this.position = 0,
    this.duration = 0,
    this.online,
    this.publishText,
  });
  final Series series;
  final String? episodeId;
  final int? episodeIndex;
  final double position;
  final double duration;
  final bool? online;
  final String? publishText;
}

class TimedComment {
  const TimedComment({required this.id, required this.text, required this.timeMs});
  final String id;
  final String text;
  final int timeMs;
}

class TimedPage {
  const TimedPage({this.startMs = 0, this.nextMs = 0, this.items = const []});
  final int startMs;
  final int nextMs;
  final List<TimedComment> items;
}

class WatchRecord {
  const WatchRecord({
    required this.series,
    required this.episodeId,
    required this.episodeIndex,
    required this.position,
    required this.duration,
    required this.completed,
    required this.updatedAt,
  });

  final Series series;
  final String episodeId;
  final int episodeIndex;
  final double position;
  final double duration;
  final bool completed;
  final int updatedAt;

  String get label {
    final seconds = position < 0 ? 0 : position.toInt();
    final clock =
        '${(seconds ~/ 60).toString().padLeft(2, '0')}:${(seconds % 60).toString().padLeft(2, '0')}';
    return '第 ${episodeIndex + 1} 集 · ${completed ? '已看完' : clock}';
  }

  Map<String, dynamic> toJson() => {
    'series': series.toJson(),
    'episodeID': episodeId,
    'episodeIndex': episodeIndex,
    'position': position,
    'duration': duration,
    'completed': completed,
    'updatedAt': updatedAt,
  };

  factory WatchRecord.fromJson(Map<String, dynamic> json) => WatchRecord(
    series: Series.fromJson(Map<String, dynamic>.from(json['series'] as Map? ?? const {})),
    episodeId: json['episodeID'] as String? ?? '',
    episodeIndex: (json['episodeIndex'] as num?)?.toInt() ?? 0,
    position: (json['position'] as num?)?.toDouble() ?? 0,
    duration: (json['duration'] as num?)?.toDouble() ?? 0,
    completed: json['completed'] as bool? ?? false,
    updatedAt: (json['updatedAt'] as num?)?.toInt() ?? 0,
  );
}

// ---- JSON helpers -------------------------------------------------------------------------

List<Map<String, dynamic>> _objects(dynamic value) {
  if (value is! List) return const [];
  return value.whereType<Map>().map((it) => Map<String, dynamic>.from(it)).toList();
}

List<Series> seriesList(dynamic value) =>
    _objects(value).map(Series.fromJson).where((it) => it.id.isNotEmpty).toList();

List<Topic> topicList(dynamic value) {
  final topics = <Topic>[];
  for (final row in _objects(value)) {
    final id = row['id'] as String? ?? '';
    if (id.isEmpty) continue;
    topics.add(Topic(id, row['label'] as String? ?? id));
  }
  return topics;
}

List<Comment> commentList(dynamic value) {
  final comments = <Comment>[];
  for (final row in _objects(value)) {
    final id = row['id'] as String? ?? '';
    if (id.isEmpty) continue;
    comments.add(
      Comment(
        id: id,
        author: row['author'] as String? ?? '',
        avatar: row['avatar'] as String? ?? '',
        content: row['content'] as String? ?? '',
        created: (row['created'] as num?)?.toInt() ?? 0,
        likes: (row['likes'] as num?)?.toInt() ?? 0,
        rating: (row['rating'] as num?)?.toDouble() ?? 0,
      ),
    );
  }
  return comments;
}

DramaDetail detailFromJson(Map<String, dynamic> json) => DramaDetail(
  series: Series.fromJson(Map<String, dynamic>.from(json['series'] as Map? ?? const {})),
  episodes: (json['episodes'] as List?)?.whereType<String>().toList() ?? const [],
);

Social socialFromJson(Map<String, dynamic> json) {
  CommentList list(String key) {
    final value = json[key];
    if (value is! Map) return const CommentList();
    return CommentList(
      total: (value['total'] as num?)?.toInt() ?? 0,
      items: commentList(value['items']),
    );
  }

  return Social(
    seriesId: json['seriesID'] as String? ?? '',
    episodeId: json['episodeID'] as String? ?? '',
    reviews: list('reviews'),
    comments: list('comments'),
    rating: (json['rating'] as num?)?.toDouble() ?? 0,
    ratingCount: (json['ratingCount'] as num?)?.toInt() ?? 0,
    sourceUrl: json['sourceURL'] as String? ?? '',
    limited: json['limited'] as bool? ?? true,
  );
}

CommentPage commentPageFromJson(Map<String, dynamic> json) => CommentPage(
  total: (json['total'] as num?)?.toInt() ?? 0,
  items: commentList(json['items']),
  nextCursor: json['nextCursor'] as String? ?? '',
  hasMore: json['hasMore'] as bool? ?? false,
);

List<CloudEntry> cloudEntriesFromJson(Map<String, dynamic> json) {
  final entries = <CloudEntry>[];
  for (final row in _objects(json['items'])) {
    final series = row['series'];
    if (series is! Map) continue;
    entries.add(
      CloudEntry(
        series: Series.fromJson(Map<String, dynamic>.from(series)),
        episodeId: (row['episodeID'] as String?)?.isNotEmpty == true
            ? row['episodeID'] as String
            : null,
        episodeIndex: (row['episodeIndex'] as num?)?.toInt(),
        position: (row['position'] as num?)?.toDouble() ?? 0,
        duration: (row['duration'] as num?)?.toDouble() ?? 0,
        online: row['online'] as bool?,
        publishText: (row['publishText'] as String?)?.isNotEmpty == true
            ? row['publishText'] as String
            : null,
      ),
    );
  }
  return entries;
}

TimedPage timedPageFromJson(Map<String, dynamic> json) => TimedPage(
  startMs: (json['startMS'] as num?)?.toInt() ?? 0,
  nextMs: (json['nextMS'] as num?)?.toInt() ?? 0,
  items: [
    for (final row in _objects(json['items']))
      if ((row['id'] as String? ?? '').isNotEmpty && (row['text'] as String? ?? '').isNotEmpty)
        TimedComment(
          id: row['id'] as String,
          text: row['text'] as String,
          timeMs: (row['timeMS'] as num?)?.toInt() ?? 0,
        ),
  ],
);
