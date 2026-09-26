import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/shared/widgets/message_bubble.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _wrap(Widget child) => MaterialApp(
      theme: AppTheme.light,
      home: Scaffold(body: child),
    );

void main() {
  group('MessageBubble', () {
    testWidgets('renders user message content', (tester) async {
      await tester.pumpWidget(_wrap(
        const MessageBubble(
          role: BubbleRole.user,
          content: 'Hello, AI!',
        ),
      ));
      expect(find.text('Hello, AI!'), findsOneWidget);
    });

    testWidgets('renders assistant message content', (tester) async {
      await tester.pumpWidget(_wrap(
        const MessageBubble(
          role: BubbleRole.assistant,
          content: 'Hi there, how can I help?',
        ),
      ));
      // flutter_markdown renders text nodes inside the widget tree
      expect(find.textContaining('Hi there'), findsOneWidget);
    });

    testWidgets('shows streaming dots when isStreaming=true and content empty',
        (tester) async {
      await tester.pumpWidget(_wrap(
        const MessageBubble(
          role: BubbleRole.assistant,
          content: '',
          isStreaming: true,
        ),
      ));
      await tester.pump(const Duration(milliseconds: 300));
      // The animated dots widget should be present
      expect(find.byType(AnimatedBuilder), findsWidgets);
    });

    testWidgets('user bubble has accessible semantics label', (tester) async {
      await tester.pumpWidget(_wrap(
        const MessageBubble(
          role: BubbleRole.user,
          content: 'Test message',
        ),
      ));
      // The Semantics widget wraps the text with 'You: Test message' label.
      expect(
        find.bySemanticsLabel(RegExp(r'You:.*Test message')),
        findsOneWidget,
      );
    });

    testWidgets('assistant bubble has accessible semantics label', (tester) async {
      await tester.pumpWidget(_wrap(
        const MessageBubble(
          role: BubbleRole.assistant,
          content: 'Response text',
        ),
      ));
      expect(
        find.bySemanticsLabel(RegExp(r'AI:.*Response text')),
        findsOneWidget,
      );
    });
  });
}
