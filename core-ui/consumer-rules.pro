# Consumer ProGuard rules for :core-ui
# These rules are applied to any module that depends on core-ui.

# Keep all public Compose composable functions so they are accessible after minification.
-keep class com.aiassistant.core.ui.** { *; }
