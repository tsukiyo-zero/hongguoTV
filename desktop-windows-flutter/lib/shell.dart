// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_info.dart';
import 'app_session.dart';
import 'screens/account_screen.dart';
import 'screens/find_screen.dart';
import 'screens/home_screen.dart';
import 'screens/library_screen.dart';
import 'screens/rank_screen.dart';
import 'screens/search_screen.dart';
import 'screens/settings_screen.dart';

class Shell extends StatefulWidget {
  const Shell({super.key, required this.session});

  final AppSession session;

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  static const _destinations = [
    _Destination('首页', Icons.home_outlined, Icons.home),
    _Destination('找剧', Icons.grid_view_outlined, Icons.grid_view),
    _Destination('榜单', Icons.bar_chart_outlined, Icons.bar_chart),
    _Destination('本机', Icons.video_library_outlined, Icons.video_library),
    _Destination('账号', Icons.person_outline, Icons.person),
    _Destination('设置', Icons.settings_outlined, Icons.settings),
  ];

  final _searchController = TextEditingController();
  final _searchFocus = FocusNode();
  final _visited = <int>{0};
  int _index = 0;
  bool _searching = false;
  String _query = '';
  int _searchTicket = 0;

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  void _select(int index) {
    setState(() {
      _index = index;
      _visited.add(index);
      _searching = false;
    });
  }

  void _submitSearch(String value) {
    final query = value.trim();
    if (query.isEmpty) return;
    setState(() {
      _query = query;
      _searching = true;
      _searchTicket += 1;
    });
    _searchFocus.unfocus();
  }

  void _closeSearch() {
    setState(() => _searching = false);
  }

  @override
  Widget build(BuildContext context) {
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.keyF, control: true): () =>
            _searchFocus.requestFocus(),
      },
      child: Focus(
        autofocus: true,
        child: Scaffold(
          body: LayoutBuilder(
            builder: (context, constraints) {
              final extended = constraints.maxWidth >= 1080;
              return Row(
                children: [
                  NavigationRail(
                    extended: extended,
                    minExtendedWidth: 180,
                    selectedIndex: _searching ? null : _index,
                    onDestinationSelected: _select,
                    leading: Padding(
                      padding: const EdgeInsets.fromLTRB(8, 18, 8, 24),
                      child: extended
                          ? Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '果映 TV',
                                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                    fontWeight: FontWeight.w800,
                                    color: Theme.of(context).colorScheme.primary,
                                  ),
                                ),
                                Text(
                                  'Windows ${AppInfo.version}',
                                  style: TextStyle(
                                    fontSize: 11,
                                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                                  ),
                                ),
                              ],
                            )
                          : Icon(
                              Icons.play_circle_fill,
                              color: Theme.of(context).colorScheme.primary,
                            ),
                    ),
                    labelType: extended
                        ? NavigationRailLabelType.none
                        : NavigationRailLabelType.all,
                    destinations: [
                      for (final destination in _destinations)
                        NavigationRailDestination(
                          icon: Icon(destination.icon),
                          selectedIcon: Icon(destination.selectedIcon),
                          label: Text(destination.label),
                        ),
                    ],
                  ),
                  const VerticalDivider(width: 1),
                  Expanded(
                    child: Column(
                      children: [
                        _topBar(context),
                        const Divider(height: 1),
                        Expanded(child: _body()),
                        _statusBar(context),
                      ],
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _topBar(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final title = _searching ? '搜索结果' : _destinations[_index].label;
    return Container(
      height: 66,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      color: scheme.surface.withValues(alpha: 0.6),
      child: Row(
        children: [
          if (_searching)
            IconButton(
              tooltip: '返回',
              onPressed: _closeSearch,
              icon: const Icon(Icons.arrow_back),
            ),
          Text(
            title,
            style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
          ),
          const Spacer(),
          SizedBox(
            width: 320,
            child: ValueListenableBuilder<TextEditingValue>(
              valueListenable: _searchController,
              builder: (context, value, _) => TextField(
                controller: _searchController,
                focusNode: _searchFocus,
                textInputAction: TextInputAction.search,
                onSubmitted: _submitSearch,
                decoration: InputDecoration(
                  hintText: '搜索全部剧集（Ctrl+F）',
                  prefixIcon: const Icon(Icons.search, size: 20),
                  suffixIcon: value.text.isEmpty
                      ? null
                      : IconButton(
                          tooltip: '清空',
                          icon: const Icon(Icons.close, size: 18),
                          onPressed: _searchController.clear,
                        ),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
              ),
            ),
          ),
          const SizedBox(width: 16),
          AnimatedBuilder(
            animation: widget.session,
            builder: (context, _) {
              final loggedIn = widget.session.loggedIn;
              return InkWell(
                onTap: () => _select(4),
                borderRadius: BorderRadius.circular(20),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  child: Row(
                    children: [
                      CircleAvatar(
                        radius: 13,
                        backgroundColor: loggedIn
                            ? scheme.primaryContainer
                            : scheme.surfaceContainerHigh,
                        child: Icon(
                          Icons.person,
                          size: 15,
                          color: loggedIn ? scheme.onPrimaryContainer : scheme.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Text(
                        loggedIn
                            ? (widget.session.account?.nickname ?? '红果用户')
                            : '未登录',
                        style: const TextStyle(fontSize: 12),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _body() {
    if (_searching) {
      return SearchScreen(
        key: ValueKey(_searchTicket),
        session: widget.session,
        query: _query,
      );
    }
    return IndexedStack(
      index: _index,
      children: [
        for (var index = 0; index < _destinations.length; index++)
          if (_visited.contains(index)) _page(index) else const SizedBox.shrink(),
      ],
    );
  }

  Widget _page(int index) => switch (index) {
    0 => HomeScreen(session: widget.session),
    1 => FindScreen(session: widget.session),
    2 => RankScreen(session: widget.session),
    3 => LibraryScreen(session: widget.session),
    4 => AccountScreen(session: widget.session),
    _ => SettingsScreen(session: widget.session),
  };

  Widget _statusBar(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      height: 28,
      padding: const EdgeInsets.symmetric(horizontal: 20),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow.withValues(alpha: 0.5),
        border: Border(top: BorderSide(color: scheme.outlineVariant)),
      ),
      child: Row(
        children: [
          Icon(Icons.circle, size: 7, color: scheme.primary),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              widget.session.bridgeStatus.isEmpty ? '本机服务已连接' : widget.session.bridgeStatus,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
            ),
          ),
          Text(
            widget.session.loggedIn ? '红果账号已登录' : '本机模式',
            style: TextStyle(fontSize: 11, color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class _Destination {
  const _Destination(this.label, this.icon, this.selectedIcon);
  final String label;
  final IconData icon;
  final IconData selectedIcon;
}
