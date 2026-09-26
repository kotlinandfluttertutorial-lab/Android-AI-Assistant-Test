/// Reusable primary button with loading state.
library;

import 'package:flutter/material.dart';

class AppButton extends StatelessWidget {
  const AppButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.isLoading = false,
    this.isDestructive = false,
    this.icon,
    this.minimumSize,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool isLoading;
  final bool isDestructive;
  final IconData? icon;
  final Size? minimumSize;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final bg = isDestructive ? colorScheme.error : colorScheme.primary;

    return ElevatedButton(
      style: ElevatedButton.styleFrom(
        backgroundColor: bg,
        foregroundColor: Colors.white,
        minimumSize: minimumSize ?? const Size(double.infinity, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        elevation: 0,
      ),
      onPressed: (isLoading || onPressed == null) ? null : onPressed,
      child: isLoading
          ? const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(
                strokeWidth: 2.5,
                color: Colors.white,
              ),
            )
          : icon != null
              ? Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(icon, size: 20),
                    const SizedBox(width: 8),
                    Text(label),
                  ],
                )
              : Text(label),
    );
  }
}

/// Secondary outlined button variant.
class AppOutlinedButton extends StatelessWidget {
  const AppOutlinedButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.isDestructive = false,
    this.icon,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool isDestructive;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final color = isDestructive
        ? Theme.of(context).colorScheme.error
        : Theme.of(context).colorScheme.primary;

    return OutlinedButton(
      style: OutlinedButton.styleFrom(
        foregroundColor: color,
        minimumSize: const Size(double.infinity, 52),
        side: BorderSide(color: color),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      onPressed: onPressed,
      child: icon != null
          ? Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon, size: 20),
                const SizedBox(width: 8),
                Text(label),
              ],
            )
          : Text(label),
    );
  }
}
