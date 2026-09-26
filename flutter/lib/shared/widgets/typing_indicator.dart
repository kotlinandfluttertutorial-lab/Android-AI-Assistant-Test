/// Three-dot bounce animation used while the AI is generating a response.
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:flutter/material.dart';

class TypingIndicator extends StatefulWidget {
  const TypingIndicator({super.key});

  @override
  State<TypingIndicator> createState() => _TypingIndicatorState();
}

class _TypingIndicatorState extends State<TypingIndicator>
    with TickerProviderStateMixin {
  late List<AnimationController> _controllers;
  late List<Animation<double>> _animations;

  static const _dotCount = 3;
  static const _period = Duration(milliseconds: 500);
  static const _staggerMs = 150;

  @override
  void initState() {
    super.initState();
    _controllers = List.generate(
      _dotCount,
      (_) => AnimationController(vsync: this, duration: _period),
    );
    _animations = _controllers.map((c) {
      return Tween<double>(begin: 0, end: -6).animate(
        CurvedAnimation(parent: c, curve: Curves.easeInOut),
      );
    }).toList();

    for (var i = 0; i < _dotCount; i++) {
      Future.delayed(Duration(milliseconds: i * _staggerMs), () {
        if (mounted) _controllers[i].repeat(reverse: true);
      });
    }
  }

  @override
  void dispose() {
    for (final c in _controllers) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = context.aiAccent;
    return Semantics(
      label: 'AI is typing',
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: List.generate(_dotCount, (i) {
          return AnimatedBuilder(
            animation: _animations[i],
            builder: (_, __) => Transform.translate(
              offset: Offset(0, _animations[i].value),
              child: Container(
                width: 8,
                height: 8,
                margin: const EdgeInsets.symmetric(horizontal: 3),
                decoration: BoxDecoration(
                  color: color,
                  shape: BoxShape.circle,
                ),
              ),
            ),
          );
        }),
      ),
    );
  }
}
