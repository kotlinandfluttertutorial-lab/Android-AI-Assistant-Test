/// Home / Dashboard screen.
///
/// Shows: greeting, AI status card, selected provider, recent conversations,
/// new chat FAB, on-device AI availability.
library;

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/core/utils/date_formatter.dart';
import 'package:ai_assistant_flutter/features/ai_providers/providers/ai_provider_provider.dart';
import 'package:ai_assistant_flutter/features/auth/providers/auth_provider.dart';
import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:ai_assistant_flutter/features/conversations/providers/conversations_provider.dart';
import 'package:ai_assistant_flutter/features/on_device_ai/domain/on_device_ai_service.dart';
import 'package:ai_assistant_flutter/features/on_device_ai/providers/on_device_ai_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState   = ref.watch(authStateProvider).valueOrNull;
    final provider    = ref.watch(selectedProviderProvider);
    final convsAsync  = ref.watch(conversationsProvider);
    final onDeviceAsync = ref.watch(onDeviceAiProvider);

    final displayName = authState?.user?.email.split('@').first ?? 'there';

    return Scaffold(
      appBar: AppBar(
        title: const Text('AI Assistant'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: 'Settings',
            onPressed: () => context.go(Routes.settings),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () =>
            ref.read(conversationsProvider.notifier).refresh(),
        child: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  // ── Greeting ───────────────────────────────────────────
                  Text(
                    _greeting(displayName),
                    style: context.texts.headlineMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'What can I help you with today?',
                    style: context.texts.bodyMedium
                        ?.copyWith(color: context.mutedColor),
                  ),

                  const SizedBox(height: 20),

                  // ── Hero status card ───────────────────────────────────
                  _HeroCard(provider: provider.displayName),

                  const SizedBox(height: 16),

                  // ── Quick actions ──────────────────────────────────────
                  _QuickActionsRow(),

                  const SizedBox(height: 16),

                  // ── On-device AI availability badge ────────────────────
                  onDeviceAsync.when(
                    loading: () => const SizedBox.shrink(),
                    error: (_, __) => const SizedBox.shrink(),
                    data: (status) => status == OnDeviceAiState.unsupported
                        ? const SizedBox.shrink()
                        : _OnDeviceBadge(status: status),
                  ),

                  const SizedBox(height: 8),

                  // ── Recent conversations header ────────────────────────
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('Recent chats',
                          style: context.texts.titleSmall),
                      TextButton(
                        onPressed: () => context.go(Routes.conversations),
                        child: const Text('See all'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                ]),
              ),
            ),

            // ── Conversation list ──────────────────────────────────────────
            convsAsync.when(
              loading: () => const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: LoadingIndicator(),
                ),
              ),
              error: (_, __) => SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    'Could not load conversations.',
                    style: TextStyle(color: context.mutedColor),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
              data: (convs) {
                if (convs.isEmpty) {
                  return SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      child: EmptyState(
                        icon: Icons.chat_bubble_outline,
                        title: 'No conversations yet',
                        subtitle: 'Tap the button below to start chatting.',
                      ),
                    ),
                  );
                }
                final recent = convs.take(5).toList();
                return SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  sliver: SliverList(
                    delegate: SliverChildBuilderDelegate(
                      (ctx, i) => _ConversationCard(conversation: recent[i]),
                      childCount: recent.length,
                    ),
                  ),
                );
              },
            ),

            const SliverPadding(padding: EdgeInsets.only(bottom: 80)),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push(Routes.newChat),
        icon: const Icon(Icons.add),
        label: const Text('New chat'),
        heroTag: 'home_new_chat',
      ),
    );
  }

  String _greeting(String name) {
    final hour = DateTime.now().hour;
    final salutation = hour < 12
        ? 'Good morning'
        : hour < 18
            ? 'Good afternoon'
            : 'Good evening';
    return '$salutation, $name';
  }
}

// ── Hero card ─────────────────────────────────────────────────────────────────

class _HeroCard extends StatelessWidget {
  const _HeroCard({required this.provider});
  final String provider;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        gradient: context.heroGradient,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.auto_awesome, color: Colors.white, size: 28,
                  semanticLabel: 'AI ready'),
              const SizedBox(width: 12),
              const Expanded(
                child: Text(
                  'AI Assistant Ready',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'Active provider: $provider',
            style: const TextStyle(
              color: Colors.white70,
              fontSize: 13,
            ),
          ),
        ],
      ),
    );
  }
}

// ── On-device badge ───────────────────────────────────────────────────────────

class _OnDeviceBadge extends StatelessWidget {
  const _OnDeviceBadge({required this.status});
  final OnDeviceAiState status;

  @override
  Widget build(BuildContext context) {
    final isReady = status == OnDeviceAiState.ready;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Chip(
        avatar: Icon(
          Icons.phone_android,
          size: 16,
          color: isReady ? context.healthy : context.mutedColor,
          semanticLabel: 'On-device AI',
        ),
        label: Text(
          isReady ? 'On-Device AI available' : 'On-Device AI: $status',
          style: TextStyle(
            fontSize: 12,
            color: isReady ? context.healthy : context.mutedColor,
          ),
        ),
      ),
    );
  }
}

// ── Conversation card ─────────────────────────────────────────────────────────

class _ConversationCard extends StatelessWidget {
  const _ConversationCard({required this.conversation});
  final Conversation conversation;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor:
              Theme.of(context).colorScheme.primary.withAlpha(20),
          child: const Icon(Icons.chat_bubble_outline, size: 18),
        ),
        title: Text(
          conversation.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: context.texts.titleSmall,
        ),
        subtitle: Text(
          DateFormatter.relative(
              conversation.updatedAt ?? conversation.createdAt),
          style: context.texts.bodySmall
              ?.copyWith(color: context.mutedColor),
        ),
        trailing: const Icon(Icons.arrow_forward_ios, size: 14),
        onTap: () => context.push(Routes.chatPath(conversation.id)),
      ),
    );
  }
}

// ── Quick actions row ─────────────────────────────────────────────────────────

class _QuickActionsRow extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Row(
          children: [
            Expanded(
              child: _QuickActionCard(
                icon:  Icons.bug_report_outlined,
                label: 'Incidents',
                color: context.critical,
                onTap: () => context.go(Routes.incidents),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _QuickActionCard(
                icon:  Icons.travel_explore,
                label: 'DevOps AI',
                color: context.aiAccent,
                onTap: () => context.go(Routes.devops),
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: _QuickActionCard(
                icon:  Icons.auto_awesome,
                label: 'Error Analysis',
                color: context.colors.primary,
                onTap: () => context.push(Routes.errorAnalysis),
              ),
            ),
          ],
        ),
      ],
    );
  }
}

class _QuickActionCard extends StatelessWidget {
  const _QuickActionCard({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  final IconData   icon;
  final String     label;
  final Color      color;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      child: InkWell(
        onTap:        onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 12),
          decoration: BoxDecoration(
            color:        color.withAlpha(15),
            borderRadius: BorderRadius.circular(12),
            border:       Border.all(color: color.withAlpha(40)),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 20, color: color, semanticLabel: label),
              const SizedBox(width: 8),
              Text(
                label,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
