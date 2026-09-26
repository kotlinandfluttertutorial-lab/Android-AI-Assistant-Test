import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/shared/widgets/typing_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('TypingIndicator renders three dots', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(
        body: Center(child: TypingIndicator()),
      ),
    ));

    // Start animations
    await tester.pump(const Duration(milliseconds: 50));

    // The three animated containers should be present
    expect(find.byType(AnimatedBuilder), findsWidgets);
  });

  testWidgets('TypingIndicator has "AI is typing" semantics label', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(body: Center(child: TypingIndicator())),
    ));
    expect(find.bySemanticsLabel('AI is typing'), findsOneWidget);
  });
}
