import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/shared/widgets/suggestion_chip_row.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _wrap(Widget child) => MaterialApp(
      theme: AppTheme.light,
      home: Scaffold(body: child),
    );

void main() {
  group('SuggestionChipRow', () {
    const suggestions = ['Ask about errors', 'Show incidents', 'Run RCA'];

    testWidgets('renders chips when visible is true', (tester) async {
      await tester.pumpWidget(_wrap(
        SuggestionChipRow(
          suggestions: suggestions,
          onTap: (_) {},
          visible: true,
        ),
      ));

      expect(find.text('Ask about errors'), findsOneWidget);
      expect(find.text('Show incidents'),  findsOneWidget);
    });

    testWidgets('renders nothing visible when visible is false', (tester) async {
      await tester.pumpWidget(_wrap(
        SuggestionChipRow(
          suggestions: suggestions,
          onTap: (_) {},
          visible: false,
        ),
      ));
      await tester.pumpAndSettle();

      // Text is not rendered when hidden.
      expect(find.text('Ask about errors'), findsNothing);
    });

    testWidgets('calls onTap with correct suggestion text', (tester) async {
      String? tapped;
      await tester.pumpWidget(_wrap(
        SuggestionChipRow(
          suggestions: suggestions,
          onTap: (s) => tapped = s,
          visible: true,
        ),
      ));

      await tester.tap(find.text('Show incidents'));
      expect(tapped, 'Show incidents');
    });

    testWidgets('renders empty widget when suggestions list is empty',
        (tester) async {
      await tester.pumpWidget(_wrap(
        SuggestionChipRow(
          suggestions: const [],
          onTap: (_) {},
          visible: true,
        ),
      ));
      // No chips — widget should render as SizedBox.shrink()
      expect(find.byType(ActionChip), findsNothing);
    });

    testWidgets('all chips have semantic labels', (tester) async {
      await tester.pumpWidget(_wrap(
        SuggestionChipRow(
          suggestions: suggestions,
          onTap: (_) {},
          visible: true,
        ),
      ));

      for (final s in suggestions) {
        expect(
          find.bySemanticsLabel('Suggestion: $s'),
          findsOneWidget,
          reason: 'Chip "$s" should have a semantics label',
        );
      }
    });
  });

  group('ChatSuggestions.defaults', () {
    test('is non-empty', () {
      expect(ChatSuggestions.defaults, isNotEmpty);
    });

    test('all defaults are non-empty strings', () {
      for (final s in ChatSuggestions.defaults) {
        expect(s.trim(), isNotEmpty);
      }
    });
  });
}
