// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter_test/flutter_test.dart';
import 'package:guoyingtv_windows/models.dart';
import 'package:guoyingtv_windows/widgets.dart';

void main() {
  test('Series round-trips through JSON', () {
    const series = Series(
      id: '1',
      title: '示例短剧',
      cover: 'https://example.com/a.heic',
      description: '描述',
      badge: '完结',
      tags: '都市,甜宠',
    );
    final restored = Series.fromJson(series.toJson());
    expect(restored.id, series.id);
    expect(restored.title, series.title);
    expect(restored.tags, series.tags);
  });

  test('WatchRecord keeps the Compose wire keys', () {
    const record = WatchRecord(
      series: Series(id: '7', title: '剧'),
      episodeId: 'e7',
      episodeIndex: 2,
      position: 65,
      duration: 300,
      completed: false,
      updatedAt: 1700000000000,
    );
    final json = record.toJson();
    expect(json['episodeID'], 'e7');
    expect(json['episodeIndex'], 2);
    final restored = WatchRecord.fromJson(json);
    expect(restored.episodeId, 'e7');
    expect(restored.label, '第 3 集 · 01:05');
  });

  test('AccountInfo accepts the credential object from the bridge', () {
    final info = AccountInfo.fromJson({
      'loggedIn': true,
      'id': '42',
      'nickname': '红果用户',
      'credentials': {'token': 'abc', 'deviceId': 'd1'},
    });
    expect(info.loggedIn, isTrue);
    expect(info.credentials?['token'], 'abc');
  });

  test('HEIC covers are rewritten to the JPEG template', () {
    expect(
      jpegFallback('https://p3-dy.fqnovelpic.com/novel-pic/abc123~tplv-heic.heic'),
      'https://p3-novel.byteimg.com/novel-pic/abc123~tplv-shrink:640:0.image',
    );
    expect(jpegFallback('https://example.com/a.jpg'), isNull);
  });
}
