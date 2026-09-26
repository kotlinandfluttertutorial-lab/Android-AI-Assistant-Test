/// Bottom-navigation shell — 5 tabs.
///
/// Tab order:
///   0  Home         (house icon)
///   1  Chats        (chat bubble)
///   2  Incidents    (bug report)
///   3  DevOps AI    (travel_explore)
///   4  Settings     (settings)
library;

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class MainShell extends StatelessWidget {
  const MainShell({super.key, required this.child});
  final Widget child;

  static const _tabs = [
    _TabItem(
      route:      Routes.home,
      icon:       Icons.home_outlined,
      activeIcon: Icons.home,
      label:      'Home',
    ),
    _TabItem(
      route:      Routes.conversations,
      icon:       Icons.chat_bubble_outline,
      activeIcon: Icons.chat_bubble,
      label:      'Chats',
    ),
    _TabItem(
      route:      Routes.incidents,
      icon:       Icons.bug_report_outlined,
      activeIcon: Icons.bug_report,
      label:      'Incidents',
    ),
    _TabItem(
      route:      Routes.devops,
      icon:       Icons.travel_explore,
      activeIcon: Icons.travel_explore,
      label:      'DevOps AI',
    ),
    _TabItem(
      route:      Routes.settings,
      icon:       Icons.settings_outlined,
      activeIcon: Icons.settings,
      label:      'Settings',
    ),
  ];

  int _activeIndex(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    if (location.startsWith(Routes.conversations)) return 1;
    if (location.startsWith(Routes.incidents))     return 2;
    if (location.startsWith(Routes.devops))        return 3;
    if (location.startsWith(Routes.settings))      return 4;
    return 0; // home
  }

  @override
  Widget build(BuildContext context) {
    final index = _activeIndex(context);
    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: index,
        onDestinationSelected: (i) => context.go(_tabs[i].route),
        destinations: _tabs
            .map(
              (t) => NavigationDestination(
                icon:         Icon(t.icon,       semanticLabel: t.label),
                selectedIcon: Icon(t.activeIcon, semanticLabel: t.label),
                label:        t.label,
                tooltip:      t.label,
              ),
            )
            .toList(),
      ),
    );
  }
}

class _TabItem {
  const _TabItem({
    required this.route,
    required this.icon,
    required this.activeIcon,
    required this.label,
  });
  final String   route;
  final IconData icon;
  final IconData activeIcon;
  final String   label;
}
