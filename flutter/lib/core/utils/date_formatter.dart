/// Date / time formatting utilities.
library;

import 'package:intl/intl.dart';

class DateFormatter {
  DateFormatter._();

  static final _time = DateFormat('HH:mm');
  static final _dateTime = DateFormat('MMM d, HH:mm');
  static final _fullDate = DateFormat('MMM d, yyyy');
  static final _iso = DateFormat("yyyy-MM-dd'T'HH:mm:ss");

  /// Format epoch millis as a short time string, e.g. "14:32".
  static String shortTime(int epochMs) {
    final dt = DateTime.fromMillisecondsSinceEpoch(epochMs, isUtc: true)
        .toLocal();
    return _time.format(dt);
  }

  /// Format epoch millis as "Nov 5, 14:32".
  static String dateTime(int epochMs) {
    final dt = DateTime.fromMillisecondsSinceEpoch(epochMs, isUtc: true)
        .toLocal();
    return _dateTime.format(dt);
  }

  /// Format a [DateTime] as a human-readable relative label.
  ///
  /// Returns "just now", "5 min ago", "2 h ago", or a full date string.
  static String relative(DateTime dt) {
    final now = DateTime.now();
    final diff = now.difference(dt);

    if (diff.inSeconds < 60) return 'just now';
    if (diff.inMinutes < 60) return '${diff.inMinutes} min ago';
    if (diff.inHours < 24) return '${diff.inHours} h ago';
    return _fullDate.format(dt.toLocal());
  }

  /// Parse an ISO-8601 string tolerantly.
  static DateTime? parseIso(String? value) {
    if (value == null || value.isEmpty) return null;
    return DateTime.tryParse(value);
  }

  /// Format a [DateTime] as "HH:mm".
  static String timeOnly(DateTime dt) => _time.format(dt.toLocal());
}
