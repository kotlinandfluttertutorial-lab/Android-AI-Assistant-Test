import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/shared/widgets/app_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _wrap(Widget child) => MaterialApp(
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      home: Scaffold(body: Center(child: child)),
    );

void main() {
  group('AppButton', () {
    testWidgets('renders label text', (tester) async {
      await tester.pumpWidget(_wrap(
        AppButton(label: 'Sign in', onPressed: () {}),
      ));
      expect(find.text('Sign in'), findsOneWidget);
    });

    testWidgets('shows CircularProgressIndicator when isLoading', (tester) async {
      await tester.pumpWidget(_wrap(
        AppButton(label: 'Sign in', onPressed: () {}, isLoading: true),
      ));
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(find.text('Sign in'), findsNothing);
    });

    testWidgets('is disabled when isLoading is true', (tester) async {
      var tapped = false;
      await tester.pumpWidget(_wrap(
        AppButton(
          label: 'Sign in',
          onPressed: () => tapped = true,
          isLoading: true,
        ),
      ));
      await tester.tap(find.byType(ElevatedButton));
      expect(tapped, isFalse);
    });

    testWidgets('calls onPressed when tapped', (tester) async {
      var tapped = false;
      await tester.pumpWidget(_wrap(
        AppButton(label: 'Go', onPressed: () => tapped = true),
      ));
      await tester.tap(find.byType(ElevatedButton));
      expect(tapped, isTrue);
    });

    testWidgets('renders icon when provided', (tester) async {
      await tester.pumpWidget(_wrap(
        AppButton(
          label: 'Send',
          onPressed: () {},
          icon: Icons.send,
        ),
      ));
      expect(find.byIcon(Icons.send), findsOneWidget);
    });
  });

  group('AppOutlinedButton', () {
    testWidgets('renders label', (tester) async {
      await tester.pumpWidget(_wrap(
        AppOutlinedButton(label: 'Cancel', onPressed: () {}),
      ));
      expect(find.text('Cancel'), findsOneWidget);
    });
  });
}
