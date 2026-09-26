/// AI Error Analysis screen.
///
/// Lets the user trigger an on-demand error analysis against recent
/// observability events and view the structured result:
///
///   ┌─────────────────────────────┐
///   │  Controls (lookback + run)  │
///   ├─────────────────────────────┤
///   │  Severity + Summary card    │
///   │  Confidence bar             │
///   │  Low-confidence warning     │
///   │  Facts ✓  /  Inferences ⚠  │
///   │  Possible causes list       │
///   │  Recommended fix            │
///   │  Evidence logs              │
///   │  Metadata (provider/events) │
///   └─────────────────────────────┘
///
/// AI Safety: recommended_fix is displayed as a suggestion only.
/// The screen never triggers any automated action.
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/analysis/domain/analysis_models.dart';
import 'package:ai_assistant_flutter/features/analysis/providers/analysis_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class ErrorAnalysisScreen extends ConsumerWidget {
  const ErrorAnalysisScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(analysisProvider);

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.auto_awesome,
                size: 20, color: context.aiAccent,
                semanticLabel: 'AI analysis'),
            const SizedBox(width: 8),
            const Text('Error Analysis'),
          ],
        ),
        actions: [
          if (state.hasResult)
            IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: 'Clear and run again',
              onPressed: () => ref.read(analysisProvider.notifier).clear(),
            ),
        ],
      ),
      body: Column(
        children: [
          // ── Controls ────────────────────────────────────────────────────
          _ControlsBar(state: state),
          const Divider(height: 1),

          // ── Content ─────────────────────────────────────────────────────
          Expanded(
            child: Builder(
              builder: (ctx) {
                if (state.isLoading) {
                  return const LoadingIndicator(
                    message: 'Running AI error analysis…',
                  );
                }
                if (state.hasError) {
                  return ErrorView(
                    message: state.error!,
                    onRetry: () =>
                        ref.read(analysisProvider.notifier).analyseRecent(),
                  );
                }
                if (!state.hasResult) {
                  return EmptyState(
                    icon:       Icons.search_outlined,
                    title:      'No analysis yet',
                    subtitle:   'Tap "Run Analysis" to analyse recent errors.',
                    actionLabel: 'Run Analysis',
                    onAction:   () =>
                        ref.read(analysisProvider.notifier).analyseRecent(),
                  );
                }
                return _AnalysisResultView(result: state.result!);
              },
            ),
          ),
        ],
      ),
    );
  }
}

// ── Controls bar ──────────────────────────────────────────────────────────────

class _ControlsBar extends ConsumerWidget {
  const _ControlsBar({required this.state});
  final AnalysisState state;

  static const _lookbacks = [
    (15,  '15 min'),
    (30,  '30 min'),
    (60,  '1 hour'),
    (360, '6 hours'),
    (1440,'24 hours'),
  ];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          // Lookback selector
          Expanded(
            child: DropdownButtonFormField<int>(
              value: state.lookbackMinutes,
              decoration: const InputDecoration(
                labelText: 'Look-back window',
                isDense: true,
                contentPadding:
                    EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              ),
              items: _lookbacks
                  .map((e) => DropdownMenuItem(
                        value: e.$1,
                        child: Text(e.$2),
                      ))
                  .toList(),
              onChanged: (v) {
                if (v != null) {
                  ref.read(analysisProvider.notifier).setLookback(v);
                }
              },
            ),
          ),
          const SizedBox(width: 12),

          // Run button
          FilledButton.icon(
            onPressed: state.isLoading
                ? null
                : () =>
                    ref.read(analysisProvider.notifier).analyseRecent(),
            icon: const Icon(Icons.play_arrow, size: 18),
            label: const Text('Run Analysis'),
          ),
        ],
      ),
    );
  }
}

// ── Result view ───────────────────────────────────────────────────────────────

class _AnalysisResultView extends StatelessWidget {
  const _AnalysisResultView({required this.result});
  final ErrorAnalysisResponse result;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        _SeveritySummaryCard(result: result),
        const SizedBox(height: 12),
        _ConfidenceSection(result: result),
        const SizedBox(height: 12),
        if (result.factsVsInference.facts.isNotEmpty ||
            result.factsVsInference.inferences.isNotEmpty)
          _FactsInferencesCard(fvi: result.factsVsInference),
        if (result.possibleCauses.isNotEmpty) ...[
          const SizedBox(height: 12),
          _PossibleCausesCard(causes: result.possibleCauses),
        ],
        const SizedBox(height: 12),
        _RecommendedFixCard(fix: result.recommendedFix),
        if (result.evidence.isNotEmpty) ...[
          const SizedBox(height: 12),
          _EvidenceCard(evidence: result.evidence),
        ],
        const SizedBox(height: 12),
        _MetadataRow(result: result),
        const SizedBox(height: 32),
      ],
    );
  }
}

// ── Severity + summary ────────────────────────────────────────────────────────

class _SeveritySummaryCard extends StatelessWidget {
  const _SeveritySummaryCard({required this.result});
  final ErrorAnalysisResponse result;

  Color _severityColor(BuildContext ctx) => switch (result.severity) {
        AnalysisSeverity.critical => ctx.critical,
        AnalysisSeverity.high     => ctx.critical.withAlpha(200),
        AnalysisSeverity.medium   => ctx.warning,
        AnalysisSeverity.low      => ctx.infoColor,
      };

  IconData _severityIcon() => switch (result.severity) {
        AnalysisSeverity.critical => Icons.error,
        AnalysisSeverity.high     => Icons.error_outline,
        AnalysisSeverity.medium   => Icons.warning_amber,
        AnalysisSeverity.low      => Icons.info_outline,
      };

  @override
  Widget build(BuildContext context) {
    final color = _severityColor(context);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: [color.withAlpha(25), color.withAlpha(8)],
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
                Icon(_severityIcon(), color: color, size: 22,
                    semanticLabel: '${result.severity.label} severity'),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 10, vertical: 3),
                  decoration: BoxDecoration(
                    color: color.withAlpha(20),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    result.severity.label,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      color: color,
                      letterSpacing: 0.5,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(result.summary, style: context.texts.titleSmall),
            const SizedBox(height: 10),
            Text(
              result.likelyRootCause,
              style: context.texts.bodyMedium,
            ),
          ],
        ),
      ),
    );
  }
}

// ── Confidence section ────────────────────────────────────────────────────────

class _ConfidenceSection extends StatelessWidget {
  const _ConfidenceSection({required this.result});
  final ErrorAnalysisResponse result;

  @override
  Widget build(BuildContext context) {
    final pct   = result.confidencePct;
    final color = result.confidence >= 0.8
        ? context.healthy
        : result.confidence >= 0.6
            ? context.warning
            : context.critical;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    Icon(Icons.verified_outlined,
                        size: 18, color: context.aiAccent,
                        semanticLabel: 'AI confidence'),
                    const SizedBox(width: 8),
                    Text('AI Confidence',
                        style: context.texts.labelMedium),
                  ],
                ),
                Text(
                  '$pct%',
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                    color: color,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value:           result.confidence,
                backgroundColor: context.cardTonal,
                color:           color,
                minHeight:       8,
              ),
            ),
            // Low-confidence warning
            if (result.hasLowConfidence) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: context.warning.withAlpha(18),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                      color: context.warning.withAlpha(60)),
                ),
                child: Row(
                  children: [
                    Icon(Icons.warning_amber,
                        size: 16, color: context.warning,
                        semanticLabel: 'Low confidence'),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        result.lowConfidenceWarning!,
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
          ],
        ),
      ),
    );
  }
}

// ── Facts vs inferences ───────────────────────────────────────────────────────

class _FactsInferencesCard extends StatelessWidget {
  const _FactsInferencesCard({required this.fvi});
  final FactsVsInference fvi;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.balance_outlined,
                    size: 18, color: context.colors.primary,
                    semanticLabel: 'Facts vs inferences'),
                const SizedBox(width: 8),
                Text('Facts vs Inferences',
                    style: context.texts.labelMedium),
              ],
            ),
            const SizedBox(height: 12),

            if (fvi.facts.isNotEmpty) ...[
              Text('Facts', style: context.texts.labelSmall
                  ?.copyWith(color: context.healthy)),
              const SizedBox(height: 4),
              ...fvi.facts.map((f) => _FactRow(
                    text:  f,
                    color: context.healthy,
                    icon:  Icons.check_circle_outline,
                  )),
            ],

            if (fvi.inferences.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text('Inferences', style: context.texts.labelSmall
                  ?.copyWith(color: context.warning)),
              const SizedBox(height: 4),
              ...fvi.inferences.map((i) => _FactRow(
                    text:  i,
                    color: context.warning,
                    icon:  Icons.warning_amber_outlined,
                  )),
            ],
          ],
        ),
      ),
    );
  }
}

class _FactRow extends StatelessWidget {
  const _FactRow({
    required this.text,
    required this.color,
    required this.icon,
  });
  final String   text;
  final Color    color;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 16, color: color,
              semanticLabel: text),
          const SizedBox(width: 8),
          Expanded(
            child: Text(text, style: context.texts.bodySmall),
          ),
        ],
      ),
    );
  }
}

// ── Possible causes ──────────────────────────────────────────────────────────

class _PossibleCausesCard extends StatelessWidget {
  const _PossibleCausesCard({required this.causes});
  final List<String> causes;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.list_alt_outlined,
                    size: 18, color: context.colors.primary,
                    semanticLabel: 'Possible causes'),
                const SizedBox(width: 8),
                Text('Possible Causes',
                    style: context.texts.labelMedium),
              ],
            ),
            const SizedBox(height: 10),
            ...causes.asMap().entries.map(
                  (e) => Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: Row(
                      crossAxisAlignment:
                          CrossAxisAlignment.start,
                      children: [
                        Container(
                          width: 20,
                          height: 20,
                          decoration: BoxDecoration(
                            color: context.colors.primary
                                .withAlpha(20),
                            shape: BoxShape.circle,
                          ),
                          child: Center(
                            child: Text(
                              '${e.key + 1}',
                              style: TextStyle(
                                fontSize: 11,
                                fontWeight: FontWeight.w700,
                                color: context.colors.primary,
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
        ),
      ),
    );
  }
}

// ── Recommended fix ───────────────────────────────────────────────────────────

class _RecommendedFixCard extends StatelessWidget {
  const _RecommendedFixCard({required this.fix});
  final String fix;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.build_circle_outlined,
                    size: 18, color: context.aiAccent,
                    semanticLabel: 'Recommended fix'),
                const SizedBox(width: 8),
                Text('Recommended Fix',
                    style: context.texts.labelMedium),
              ],
            ),
            const SizedBox(height: 4),
            // Safety disclaimer
            Text(
              'AI suggestion only — review before acting',
              style: TextStyle(
                fontSize: 11,
                color: context.mutedColor,
                fontStyle: FontStyle.italic,
              ),
            ),
            const SizedBox(height: 10),
            Text(fix, style: context.texts.bodyMedium),
          ],
        ),
      ),
    );
  }
}

// ── Evidence ──────────────────────────────────────────────────────────────────

class _EvidenceCard extends StatefulWidget {
  const _EvidenceCard({required this.evidence});
  final List<String> evidence;

  @override
  State<_EvidenceCard> createState() => _EvidenceCardState();
}

class _EvidenceCardState extends State<_EvidenceCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final shown = _expanded
        ? widget.evidence
        : widget.evidence.take(3).toList();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.receipt_long_outlined,
                    size: 18, color: context.colors.primary,
                    semanticLabel: 'Evidence'),
                const SizedBox(width: 8),
                Text('Evidence (${widget.evidence.length})',
                    style: context.texts.labelMedium),
              ],
            ),
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color:        context.cardTonal,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: shown
                    .map(
                      (e) => Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: Text(
                          e,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize:   12,
                            height:     1.4,
                          ),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ),
            if (widget.evidence.length > 3)
              TextButton(
                onPressed: () =>
                    setState(() => _expanded = !_expanded),
                child: Text(
                  _expanded
                      ? 'Show less'
                      : 'Show all ${widget.evidence.length} items',
                ),
              ),
          ],
        ),
      ),
    );
  }
}

// ── Metadata row ──────────────────────────────────────────────────────────────

class _MetadataRow extends StatelessWidget {
  const _MetadataRow({required this.result});
  final ErrorAnalysisResponse result;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 6,
      children: [
        if (result.llmProvider.isNotEmpty)
          _MetaChip(
            icon:  Icons.memory_outlined,
            label: result.llmProvider,
          ),
        _MetaChip(
          icon:  Icons.event_note_outlined,
          label: '${result.eventsAnalysed} events',
        ),
        if (result.knowledgeChunksRetrieved > 0)
          _MetaChip(
            icon:  Icons.library_books_outlined,
            label: '${result.knowledgeChunksRetrieved} docs',
          ),
      ],
    );
  }
}

class _MetaChip extends StatelessWidget {
  const _MetaChip({required this.icon, required this.label});
  final IconData icon;
  final String   label;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: context.mutedColor,
            semanticLabel: label),
        const SizedBox(width: 4),
        Text(
          label,
          style: context.texts.labelSmall
              ?.copyWith(color: context.mutedColor),
        ),
      ],
    );
  }
}
