// SPDX-License-Identifier: GPL-3.0-only
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:guoyingtv_windows/api.dart';
import 'package:guoyingtv_windows/bridge.dart';

/// End-to-end check against the real loopback bridge. It needs a built bridge and a JDK, so
/// it is opt-in and never runs as part of the default `flutter test`:
///
/// ```powershell
/// $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
/// $env:GUOYING_BRIDGE_DIR = 'desktop-mac\backend\build\install\bridge\lib'
/// $env:GUOYING_LIVE_BRIDGE = '1'
/// flutter test test/live_bridge_test.dart
/// ```
///
/// It verifies the parts the UI cannot show in a widget test: URL/token handling, JSON
/// parsing for every screen, and range-seekable playback from the loopback video endpoint.
void main() {
  final enabled = Platform.environment['GUOYING_LIVE_BRIDGE'] == '1';
  test(
    'live bridge serves feed, detail, play, comments and danmaku',
    () async {
      final bridge = await Bridge.launch();
      final client = HttpClient();
      try {
        final api = Api(bridge);

        final feed = await api.feed(refresh: true);
        expect(feed.items, isNotEmpty, reason: '首页推荐应返回剧集');
        final series = feed.items.first;
        expect(series.title, isNotEmpty);

        final detail = await api.detail(series.id);
        expect(detail.series.id, series.id);
        expect(detail.episodes, isNotEmpty, reason: '详情应包含选集');
        expect(detail.episodes.length, greaterThan(1));

        final related = await api.related(series.id);
        for (final entry in related) {
          expect(entry.series.id, isNotEmpty);
        }

        final page = await api.catalog('COMIC', page: 1);
        expect(page.items, isNotEmpty);
        expect(page.topics, isNotEmpty);

        final rank = await api.rank('COMIC');
        expect(rank.items, isNotEmpty);
        expect(rank.positions, isNotEmpty);

        final comments = await api.comments(series.id, detail.episodes.first);
        expect(comments.comments.total >= comments.comments.items.length, isTrue);
        final commentPage = await api.commentPage(series.id, detail.episodes.first, '');
        expect(commentPage.total >= commentPage.items.length, isTrue);

        final danmaku = await api.danmaku(
          series.id,
          detail.episodes.first,
          startMs: 0,
          durationMs: 60000,
        );
        expect(danmaku.nextMs, greaterThanOrEqualTo(0));

        // The play endpoint hands back a loopback URL; the player relies on it being
        // seekable, so Range support is part of the contract.
        final play = await api.play(detail.episodes.first, quality: 720);
        expect(play.url, startsWith('http://127.0.0.1:'));
        final request = await client.getUrl(Uri.parse(play.url));
        request.headers.set(HttpHeaders.rangeHeader, 'bytes=0-1023');
        final response = await request.close();
        expect(response.statusCode, anyOf(200, 206));
        final bytes = await response.fold<int>(0, (sum, chunk) => sum + chunk.length);
        expect(bytes, greaterThan(0), reason: '视频端点应返回数据');
      } finally {
        client.close(force: true);
        await bridge.close();
      }
    },
    timeout: const Timeout(Duration(minutes: 5)),
    skip: enabled
        ? false
        : '设置 GUOYING_LIVE_BRIDGE=1、JAVA_HOME 与 GUOYING_BRIDGE_DIR 后单独运行',
  );
}
