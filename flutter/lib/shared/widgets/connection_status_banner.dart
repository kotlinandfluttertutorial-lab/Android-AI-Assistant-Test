/// Offline / reconnecting banner shown at the top of screens when connectivity
/// is lost.
///
/// Shows automatically when [isOffline] is true; animates in/out.
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:flutter/material.dart';

class ConnectionStatusBanner extends StatelessWidget {
  const ConnectionStatusBanner({
    super.key,
    required this.isOffline,
  });

  final bool isOffline;

  @override
  Widget build(BuildContext context) {
    return AnimatedSize(
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeInOut,
      child: isOffline
          ? Container(
              width: double.infinity,
              color: context.warning.withAlpha(220),
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
              child: Row(
                children: [
                  Icon(Icons.wifi_off, size: 18, color: Colors.white,
                      semanticLabel: 'Offline'),
                  const SizedBox(width: 8),
                  const Text(
                    'No internet connection',
                    style: TextStyle(color: Colors.white, fontSize: 13),
                  ),
                ],
              ),
            )
          : const SizedBox.shrink(),
    );
  }
}
