/// Adaptive navigation shell.
///
/// Phone  (< 600dp wide)  → [NavigationBar] at bottom (Material 3)
/// Tablet (≥ 600dp wide)  → [NavigationRail] on the left side
///
/// On tablet the child occupies the remaining space. On very wide screens
/// (≥ 900dp) the rail is always expanded (shows labels inline); on medium
/// screens it is collapsed (icon-only) and labels appear as tooltips.
///
/// The tab list, active-index logic, and route targets are shared between
/// both variants to keep them in sync.
library;

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

// ── Shared tab definitions ────────────────────────────────────────────────────

class _Tab {
  const _Tab({
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

const List<_Tab> _tabs = [
  _Tab(
    route:      Routes.home,
    icon:       Icons.home_outlined,
    activeIcon: Icons.home,
    label:      'Home',
  ),
  _Tab(
    route:      Routes.conversations,
    icon:       Icons.chat_bubble_outline,
    activeIcon: Icons.chat_bubble,
    label:      'Chats',
  ),
  _Tab(
    route:      Routes.incidents,
    icon:       Icons.bug_report_outlined,
    activeIcon: Icons.bug_report,
    label:      'Incidents',
  ),
  _Tab(
    route:      Routes.devops,
    icon:       Icons.travel_explore,
    activeIcon: Icons.travel_explore,
    label:      'DevOps AI',
  ),
  _Tab(
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
  return 0;
}

// ── AdaptiveShell ─────────────────────────────────────────────────────────────

class AdaptiveShell extends StatelessWidget {
  const AdaptiveShell({super.key, required this.child});
  final Widget child;

  /// Minimum width (dp) to switch from BottomNav to NavigationRail.
  static const double _railBreakpoint = 600;

  /// Minimum width to expand the rail (show labels inline).
  static const double _extendedBreakpoint = 900;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.of(context).size.width;
    final index = _activeIndex(context);

    if (width < _railBreakpoint) {
      // ── Phone layout ───────────────────────────────────────────────────
      return Scaffold(
        body: child,
        bottomNavigationBar: NavigationBar(
          selectedIndex:        index,
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

    // ── Tablet layout ───────────────────────────────────────────────────────
    final extended = width >= _extendedBreakpoint;

    return Scaffold(
      body: Row(
        children: [
          // Navigation rail
          NavigationRail(
            extended:      extended,
            selectedIndex: index,
            minWidth:      72,
            minExtendedWidth: 180,
            backgroundColor: Theme.of(context).colorScheme.surface,
            indicatorColor:
                Theme.of(context).colorScheme.primary.withAlpha(25),
            selectedIconTheme: IconThemeData(
              color: Theme.of(context).colorScheme.primary,
            ),
            unselectedIconTheme: IconThemeData(
              color: context.mutedColor,
            ),
            selectedLabelTextStyle: TextStyle(
              color:      Theme.of(context).colorScheme.primary,
              fontWeight: FontWeight.w600,
              fontSize:   13,
            ),
            unselectedLabelTextStyle: TextStyle(
              color:    context.mutedColor,
              fontSize: 13,
            ),
            onDestinationSelected: (i) => context.go(_tabs[i].route),
            // Leading: app logo mark
            leading: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Container(
                width:  40,
                height: 40,
                decoration: BoxDecoration(
                  gradient: context.heroGradient,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(
                  Icons.auto_awesome,
                  size: 20,
                  color: Colors.white,
                  semanticLabel: 'AI Assistant',
                ),
              ),
            ),
            destinations: _tabs
                .map(
                  (t) => NavigationRailDestination(
                    icon:         Icon(t.icon,       semanticLabel: t.label),
                    selectedIcon: Icon(t.activeIcon, semanticLabel: t.label),
                    label:        Text(t.label),
                    padding:      const EdgeInsets.symmetric(vertical: 2),
                  ),
                )
                .toList(),
          ),

          // Vertical divider
          const VerticalDivider(width: 1, thickness: 1),

          // Content area
          Expanded(child: child),
        ],
      ),
    );
  }
}
