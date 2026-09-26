import 'package:ai_assistant_flutter/core/utils/date_formatter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('DateFormatter', () {
    test('relative returns "just now" for <60 seconds ago', () {
      final now = DateTime.now().subtract(const Duration(seconds: 10));
      expect(DateFormatter.relative(now), 'just now');
    });

    test('relative returns "N min ago" for <60 minutes', () {
      final past = DateTime.now().subtract(const Duration(minutes: 5));
      expect(DateFormatter.relative(past), '5 min ago');
    });

    test('relative returns "N h ago" for <24 hours', () {
      final past = DateTime.now().subtract(const Duration(hours: 3));
      expect(DateFormatter.relative(past), '3 h ago');
    });

    test('relative returns full date for >= 24 hours', () {
      final past = DateTime.now().subtract(const Duration(days: 2));
      final result = DateFormatter.relative(past);
      // Should contain the month abbreviation, not 'h ago' or 'min ago'
      expect(result, isNot(contains('ago')));
      expect(result.isNotEmpty, isTrue);
    });

    test('parseIso returns null for null input', () {
      expect(DateFormatter.parseIso(null), isNull);
    });

    test('parseIso returns null for empty string', () {
      expect(DateFormatter.parseIso(''), isNull);
    });

    test('parseIso parses valid ISO-8601 string', () {
      final dt = DateFormatter.parseIso('2025-06-15T12:30:00');
      expect(dt, isNotNull);
      expect(dt!.year, 2025);
      expect(dt.month, 6);
      expect(dt.day, 15);
    });
  });
}
