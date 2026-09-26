/// Reusable card with optional accent border and tonal variants.
library;

import 'package:flutter/material.dart';

class AppCard extends StatelessWidget {
  const AppCard({
    super.key,
    required this.child,
    this.padding,
    this.accentColor,
    this.accentWidth = 4,
    this.elevation = 2,
    this.onTap,
  });

  final Widget child;
  final EdgeInsetsGeometry? padding;

  /// If set, a vertical colored stripe is drawn on the left edge.
  final Color? accentColor;
  final double accentWidth;
  final double elevation;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final content = padding != null
        ? Padding(padding: padding!, child: child)
        : child;

    Widget card = Card(
      elevation: elevation,
      clipBehavior: Clip.antiAlias,
      child: accentColor != null
          ? IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Container(width: accentWidth, color: accentColor),
                  Expanded(child: content),
                ],
              ),
            )
          : content,
    );

    if (onTap != null) {
      card = InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: card,
      );
    }

    return card;
  }
}
