/// Incident detail screen.
///
/// Shows: incident header, AI root cause analysis card, suggested fix steps,
/// human approval/reject flow, evidence timeline, and raw log count.
///
/// AI Safety: Approve/Reject buttons are only shown for RECOMMENDED actions.
/// High-risk actions show an extra confirmation dialog.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/incidents/domain/incident_models.dart';
import 'package:ai_assistant_flutter/features/incidents/providers/incidents_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class IncidentDetailScreen extends ConsumerWidget {
  const IncidentDetailScreen({super.key, required this.incidentId});
  final String incidentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final incidentAsync = ref.watch(incidentDetailProvider(incidentId));
    final rcaAsync      = ref.watch(rcaProvider(incidentId));

    return Scaffold(
      appBar: AppBar(
        title: Text(
          'INC-${incidentId.substring(0, 8).toUpperCase()}',
          style: const TextStyle(fontFamily: 'monospace', fontSize: 14),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () {
              ref.invalidate(incidentDetailProvider(incidentId));
              ref.invalidate(rcaProvider(incidentId));
            },
          ),
        ],
      ),
      body: incidentAsync.when(
        loading: () => const LoadingIndicator(message: 'Loading incident…'),
        error:   (e, _) => ErrorView(message: e.toString()),
        data:    (incident) => _IncidentDetailBody(
          incident:   incident,
          rcaAsync:   rcaAsync,
          incidentId: incidentId,
        ),
      ),
    );
  }
}

// ── Detail body ───────────────────────────────────────────────────────────────

class _IncidentDetailBody extends ConsumerWidget {
  const _IncidentDetailBody({
    required this.incident,
    required this.rcaAsync,
    required this.incidentId,
  });

  final Incident incident;
  final AsyncValue<RcaAnalysisResponse?> rcaAsync;
  final String incidentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // ── Header card ────────────────────────────────────────────────
        _IncidentHeaderCard(incident: incident),
        const SizedBox(height: 16),

        // ── RCA section ────────────────────────────────────────────────
        _RcaSection(
          incidentId: incidentId,
          rcaAsync:   rcaAsync,
        ),
        const SizedBox(height: 16),

        // ── Remediation section ────────────────────────────────────────
        _RemediationSection(incidentId: incidentId),
        const SizedBox(height: 32),
      ],
    );
  }
}

// ── Header card ───────────────────────────────────────────────────────────────

class _IncidentHeaderCard extends StatelessWidget {
  const _IncidentHeaderCard({required this.incident});
  final Incident incident;

  Color _severityColor(BuildContext ctx) => switch (incident.severity) {
        IncidentSeverity.critical => ctx.critical,
        IncidentSeverity.high     => ctx.critical.withAlpha(200),
        IncidentSeverity.medium   => ctx.warning,
        IncidentSeverity.low      => ctx.infoColor,
      };

  @override
  Widget build(BuildContext context) {
    final color = _severityColor(context);

    return Card(
      clipBehavior: Clip.antiAlias,
      child: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: [color.withAlpha(30), color.withAlpha(10)],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.bug_report_outlined,
                    color: color, semanticLabel: 'Incident'),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    incident.title,
                    style: context.texts.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                _InfoChip(
                  label: incident.severity.label,
                  color: color,
                ),
                _InfoChip(
                  label: incident.status.label,
                  color: context.mutedColor,
                ),
                _InfoChip(
                  label: '${incident.eventCount} events',
                  color: context.mutedColor,
                ),
                if (incident.triggeredBy.isNotEmpty)
                  _InfoChip(
                    label: incident.triggeredBy,
                    color: context.mutedColor,
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Detected ${incident.detectedAt}',
              style: context.texts.bodySmall
                  ?.copyWith(color: context.mutedColor),
            ),
          ],
        ),
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  const _InfoChip({required this.label, required this.color});
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withAlpha(20),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: color,
          letterSpacing: 0.4,
        ),
      ),
    );
  }
}

// ── RCA section ───────────────────────────────────────────────────────────────

class _RcaSection extends ConsumerWidget {
  const _RcaSection({
    required this.incidentId,
    required this.rcaAsync,
  });
  final String incidentId;
  final AsyncValue<RcaAnalysisResponse?> rcaAsync;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(Icons.auto_awesome,
                    size: 18, color: context.aiAccent,
                    semanticLabel: 'AI analysis'),
                const SizedBox(width: 8),
                Text('Root Cause Analysis',
                    style: context.texts.titleSmall),
              ],
            ),
            TextButton.icon(
              onPressed: () => ref
                  .read(rcaProvider(incidentId).notifier)
                  .runRca(),
              icon: const Icon(Icons.play_arrow, size: 16),
              label: const Text('Run RCA'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        rcaAsync.when(
          loading: () => const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator.adaptive(),
            ),
          ),
          error: (e, _) => Text(
            'RCA unavailable: ${e.toString()}',
            style: TextStyle(color: context.mutedColor, fontSize: 13),
          ),
          data: (rca) {
            if (rca == null) {
              return Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: context.cardTonal,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  'No RCA has been run yet. Tap "Run RCA" to analyse this incident.',
                  style: context.texts.bodySmall
                      ?.copyWith(color: context.mutedColor),
                ),
              );
            }
            return _RcaCard(rca: rca);
          },
        ),
      ],
    );
  }
}

class _RcaCard extends StatelessWidget {
  const _RcaCard({required this.rca});
  final RcaAnalysisResponse rca;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Summary
            Text(rca.summary, style: context.texts.bodyMedium),
            const SizedBox(height: 12),

            // Overall confidence bar
            _ConfidenceBar(
              confidence: rca.overallConfidence,
              label: 'Overall confidence',
            ),

            // Low-confidence warning
            if (rca.hasLowConfidence) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: context.warning.withAlpha(20),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: context.warning.withAlpha(60)),
                ),
                child: Row(
                  children: [
                    Icon(Icons.warning_amber,
                        size: 16, color: context.warning,
                        semanticLabel: 'Low confidence warning'),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        rca.lowConfidenceWarning!,
                        style: TextStyle(
                          fontSize: 12,
                          color: context.warning,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],

            // Top candidates
            if (rca.rootCauseCandidates.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text('Root cause candidates',
                  style: context.texts.labelMedium),
              const SizedBox(height: 8),
              ...rca.rootCauseCandidates
                  .take(3)
                  .map((c) => _CandidateRow(candidate: c)),
            ],

            // Investigation steps
            if (rca.investigationSteps.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text('Investigation steps',
                  style: context.texts.labelMedium),
              const SizedBox(height: 8),
              ...rca.investigationSteps.asMap().entries.map(
                    (e) => Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Container(
                            width: 20,
                            height: 20,
                            decoration: BoxDecoration(
                              color: context.aiAccent.withAlpha(30),
                              shape: BoxShape.circle,
                            ),
                            child: Center(
                              child: Text(
                                '${e.key + 1}',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w700,
                                  color: context.aiAccent,
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(e.value,
                                style: context.texts.bodySmall),
                          ),
                        ],
                      ),
                    ),
                  ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ConfidenceBar extends StatelessWidget {
  const _ConfidenceBar({
    required this.confidence,
    required this.label,
  });
  final double confidence;
  final String label;

  @override
  Widget build(BuildContext context) {
    final pct = (confidence * 100).round();
    final color = confidence >= 0.8
        ? context.healthy
        : confidence >= 0.6
            ? context.warning
            : context.critical;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label,
                style: context.texts.bodySmall
                    ?.copyWith(color: context.mutedColor)),
            Text(
              '$pct%',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w700,
                color: color,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: LinearProgressIndicator(
            value: confidence,
            backgroundColor: context.cardTonal,
            color: color,
            minHeight: 6,
          ),
        ),
      ],
    );
  }
}

class _CandidateRow extends StatelessWidget {
  const _CandidateRow({required this.candidate});
  final RootCauseCandidate candidate;

  @override
  Widget build(BuildContext context) {
    final pct = (candidate.confidence * 100).round();
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              color: context.aiAccent.withAlpha(20),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Center(
              child: Text(
                '#${candidate.rank}',
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  color: context.aiAccent,
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(candidate.cause,
                style: context.texts.bodySmall),
          ),
          Text(
            '$pct%',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: context.aiAccent,
            ),
          ),
        ],
      ),
    );
  }
}

// ── Remediation section ───────────────────────────────────────────────────────

class _RemediationSection extends ConsumerWidget {
  const _RemediationSection({required this.incidentId});
  final String incidentId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final remAsync = ref.watch(remediationProvider(incidentId));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(Icons.build_circle_outlined,
                    size: 18, color: context.colors.primary,
                    semanticLabel: 'Remediation'),
                const SizedBox(width: 8),
                Text('Remediation', style: context.texts.titleSmall),
              ],
            ),
            TextButton.icon(
              onPressed: () => ref
                  .read(remediationProvider(incidentId).notifier)
                  .recommend(),
              icon: const Icon(Icons.auto_awesome, size: 16),
              label: const Text('Recommend'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        remAsync.when(
          loading: () => const Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: CircularProgressIndicator.adaptive(),
            ),
          ),
          error: (e, _) => Text(
            'Remediation unavailable: ${e.toString()}',
            style: TextStyle(color: context.mutedColor, fontSize: 13),
          ),
          data: (plan) {
            if (plan == null) {
              return Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: context.cardTonal,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  'Tap "Recommend" to generate AI-suggested remediation actions.',
                  style: context.texts.bodySmall
                      ?.copyWith(color: context.mutedColor),
                ),
              );
            }
            return Column(
              children: plan.actions
                  .map((a) => _RemediationActionCard(
                        action:     a,
                        incidentId: incidentId,
                      ))
                  .toList(),
            );
          },
        ),
      ],
    );
  }
}

class _RemediationActionCard extends ConsumerWidget {
  const _RemediationActionCard({
    required this.action,
    required this.incidentId,
  });
  final RemediationAction action;
  final String incidentId;

  Color _riskColor(BuildContext ctx) => switch (action.riskTier.toUpperCase()) {
        'HIGH'   => ctx.critical,
        'MEDIUM' => ctx.warning,
        _        => ctx.healthy,
      };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final riskColor = _riskColor(context);

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Title + risk badge
            Row(
              children: [
                Expanded(
                  child: Text(action.title,
                      style: context.texts.titleSmall),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: riskColor.withAlpha(20),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    '${action.riskTier} RISK',
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: riskColor,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(action.reasoning,
                style: context.texts.bodySmall
                    ?.copyWith(color: context.mutedColor)),

            // Approval buttons — only for pending actions
            if (action.isPending) ...[
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: context.healthy,
                        foregroundColor: Colors.white,
                        minimumSize: const Size(0, 40),
                        elevation: 0,
                      ),
                      icon: const Icon(Icons.how_to_reg, size: 16,
                          semanticLabel: 'Approve fix'),
                      label: const Text('Approve'),
                      onPressed: () => _approve(context, ref),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        foregroundColor: context.critical,
                        side: BorderSide(color: context.critical),
                        minimumSize: const Size(0, 40),
                      ),
                      icon: const Icon(Icons.do_not_disturb_on_outlined,
                          size: 16, semanticLabel: 'Reject fix'),
                      label: const Text('Reject'),
                      onPressed: () => _reject(context, ref),
                    ),
                  ),
                ],
              ),
              if (action.isHighRisk)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Row(
                    children: [
                      Icon(Icons.warning_amber,
                          size: 14, color: context.critical,
                          semanticLabel: 'High risk warning'),
                      const SizedBox(width: 4),
                      Text(
                        'High-risk action — manual execution required after approval.',
                        style: TextStyle(
                          fontSize: 11,
                          color: context.critical,
                        ),
                      ),
                    ],
                  ),
                ),
            ],

            // Status when already reviewed
            if (!action.isPending)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  action.isApproved
                      ? '✓ Approved'
                      : '✗ Rejected${action.rejectionReason != null ? ": ${action.rejectionReason}" : ""}',
                  style: TextStyle(
                    fontSize: 12,
                    color:
                        action.isApproved ? context.healthy : context.critical,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _approve(BuildContext context, WidgetRef ref) async {
    // Extra confirmation for high-risk actions.
    if (action.isHighRisk) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Approve high-risk action?'),
          content: Text(
            'This action is rated HIGH risk.\n\n${action.title}\n\n'
            'Approval records your decision but does NOT automatically execute the action. '
            'You must execute it manually using the params provided.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: context.critical,
                foregroundColor: Colors.white,
              ),
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Approve anyway'),
            ),
          ],
        ),
      );
      if (confirmed != true) return;
    }

    await ref
        .read(remediationProvider(incidentId).notifier)
        .approve(action.id);
  }

  Future<void> _reject(BuildContext context, WidgetRef ref) async {
    await ref
        .read(remediationProvider(incidentId).notifier)
        .reject(action.id);
  }
}
