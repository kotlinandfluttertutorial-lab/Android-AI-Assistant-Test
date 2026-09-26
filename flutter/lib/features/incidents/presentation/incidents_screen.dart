/// Incidents list screen.
///
/// Shows all incidents with filter chips (All / Critical / High / Med / Low)
/// and severity-colored cards. Tapping a card opens the detail screen.
library;

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/core/utils/date_formatter.dart';
import 'package:ai_assistant_flutter/features/incidents/domain/incident_models.dart';
import 'package:ai_assistant_flutter/features/incidents/providers/incidents_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class IncidentsScreen extends ConsumerWidget {
  const IncidentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final filter   = ref.watch(incidentFilterProvider);
    final listAsync = ref.watch(incidentsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Incidents'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () =>
                ref.read(incidentsProvider.notifier).refresh(),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Severity filter chips ──────────────────────────────────────
          _FilterRow(
            current: filter,
            onChanged: (f) =>
                ref.read(incidentsProvider.notifier).setFilter(f),
          ),
          const Divider(height: 1),

          // ── Stats row ──────────────────────────────────────────────────
          listAsync.whenData((data) =>
              _StatsBar(total: data.total, openCount: data.openCount)),

          // ── List ───────────────────────────────────────────────────────
          Expanded(
            child: listAsync.when(
              loading: () =>
                  const LoadingIndicator(message: 'Loading incidents…'),
              error: (e, _) => ErrorView(
                message: e.toString(),
                onRetry: () =>
                    ref.read(incidentsProvider.notifier).refresh(),
              ),
              data: (data) {
                if (data.incidents.isEmpty) {
                  return const EmptyState(
                    icon: Icons.bug_report_outlined,
                    title: 'No incidents',
                    subtitle: 'All systems are operating normally.',
                  );
                }
                return RefreshIndicator(
                  onRefresh: () =>
                      ref.read(incidentsProvider.notifier).refresh(),
                  child: ListView.separated(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    itemCount: data.incidents.length,
                    separatorBuilder: (_, __) =>
                        const SizedBox(height: 4),
                    itemBuilder: (ctx, i) =>
                        _IncidentCard(incident: data.incidents[i]),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

// ── Filter row ────────────────────────────────────────────────────────────────

class _FilterRow extends StatelessWidget {
  const _FilterRow({required this.current, required this.onChanged});
  final IncidentFilter current;
  final void Function(IncidentFilter) onChanged;

  static const _severities = [
    (null, 'All'),
    ('CRITICAL', 'Critical'),
    ('HIGH', 'High'),
    ('MEDIUM', 'Medium'),
    ('LOW', 'Low'),
  ];

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        children: _severities.map((entry) {
          final (value, label) = entry;
          final selected = current.severity == value;
          return Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilterChip(
              label: Text(label),
              selected: selected,
              onSelected: (_) =>
                  onChanged(IncidentFilter(severity: value)),
              avatar: value != null
                  ? _SeverityDot(severity: value)
                  : null,
            ),
          );
        }).toList(),
      ),
    );
  }
}

class _SeverityDot extends StatelessWidget {
  const _SeverityDot({required this.severity});
  final String severity;

  @override
  Widget build(BuildContext context) {
    final color = _color(context, severity);
    return Container(
      width: 10,
      height: 10,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
    );
  }

  Color _color(BuildContext ctx, String s) => switch (s) {
        'CRITICAL' => ctx.critical,
        'HIGH'     => ctx.critical.withAlpha(180),
        'MEDIUM'   => ctx.warning,
        _          => ctx.infoColor,
      };
}

// ── Stats bar ─────────────────────────────────────────────────────────────────

class _StatsBar extends StatelessWidget {
  const _StatsBar({required this.total, required this.openCount});
  final int total;
  final int openCount;

  @override
  Widget build(BuildContext context) {
    if (total == 0) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          _Stat(label: 'Total', value: total, color: context.mutedColor),
          const SizedBox(width: 16),
          _Stat(
            label: 'Open',
            value: openCount,
            color: openCount > 0 ? context.critical : context.healthy,
          ),
        ],
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat({
    required this.label,
    required this.value,
    required this.color,
  });
  final String label;
  final int value;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          '$value',
          style: TextStyle(
            fontSize: 18,
            fontWeight: FontWeight.w700,
            color: color,
          ),
        ),
        const SizedBox(width: 4),
        Text(
          label,
          style: context.texts.bodySmall?.copyWith(color: context.mutedColor),
        ),
      ],
    );
  }
}

// ── Incident card ─────────────────────────────────────────────────────────────

class _IncidentCard extends StatelessWidget {
  const _IncidentCard({required this.incident});
  final Incident incident;

  Color _borderColor(BuildContext ctx) => switch (incident.severity) {
        IncidentSeverity.critical => ctx.critical,
        IncidentSeverity.high     => ctx.critical.withAlpha(200),
        IncidentSeverity.medium   => ctx.warning,
        IncidentSeverity.low      => ctx.infoColor,
      };

  IconData _severityIcon() => switch (incident.severity) {
        IncidentSeverity.critical => Icons.error,
        IncidentSeverity.high     => Icons.error_outline,
        IncidentSeverity.medium   => Icons.warning_amber,
        IncidentSeverity.low      => Icons.info_outline,
      };

  @override
  Widget build(BuildContext context) {
    final borderColor = _borderColor(context);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Severity accent border
              Container(width: 4, color: borderColor),

              // Card content
              Expanded(
                child: InkWell(
                  onTap: () =>
                      context.push(Routes.incidentDetail(incident.id)),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              _severityIcon(),
                              size: 18,
                              color: borderColor,
                              semanticLabel:
                                  '${incident.severity.label} severity',
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                incident.title,
                                style: context.texts.titleSmall,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                            _StatusChip(status: incident.status),
                          ],
                        ),
                        const SizedBox(height: 6),
                        Row(
                          children: [
                            Text(
                              incident.severity.label,
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                                color: borderColor,
                                letterSpacing: 0.5,
                              ),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              '•',
                              style: TextStyle(color: context.mutedColor),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              '${incident.eventCount} events',
                              style: context.texts.bodySmall
                                  ?.copyWith(color: context.mutedColor),
                            ),
                            const Spacer(),
                            Text(
                              _formatTime(incident.detectedAt),
                              style: context.texts.labelSmall
                                  ?.copyWith(color: context.mutedColor),
                            ),
                          ],
                        ),
                        // AI summary line
                        if (incident.aiSummary != null) ...[
                          const SizedBox(height: 6),
                          Row(
                            children: [
                              Icon(
                                Icons.auto_awesome,
                                size: 14,
                                color: context.aiAccent,
                                semanticLabel: 'AI analysis',
                              ),
                              const SizedBox(width: 4),
                              Expanded(
                                child: Text(
                                  incident.aiSummary!,
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: context.aiAccent,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              if (incident.aiConfidence != null)
                                Text(
                                  '${(incident.aiConfidence! * 100).round()}%',
                                  style: TextStyle(
                                    fontSize: 12,
                                    fontWeight: FontWeight.w600,
                                    color: context.aiAccent,
                                  ),
                                ),
                            ],
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  String _formatTime(String isoStr) {
    final dt = DateFormatter.parseIso(isoStr);
    if (dt == null) return isoStr;
    return DateFormatter.relative(dt);
  }
}

// ── Status chip ───────────────────────────────────────────────────────────────

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});
  final IncidentStatus status;

  @override
  Widget build(BuildContext context) {
    final (color, bg) = switch (status) {
      IncidentStatus.open => (
          context.critical,
          context.critical.withAlpha(20),
        ),
      IncidentStatus.investigating => (
          context.warning,
          context.warning.withAlpha(20),
        ),
      IncidentStatus.resolved => (
          context.healthy,
          context.healthy.withAlpha(20),
        ),
      IncidentStatus.dismissed => (
          context.mutedColor,
          context.mutedColor.withAlpha(20),
        ),
    };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        status.label,
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: color,
          letterSpacing: 0.5,
        ),
      ),
    );
  }
}
