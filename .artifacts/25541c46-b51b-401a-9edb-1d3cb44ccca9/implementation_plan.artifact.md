# Fix Variant Selection Ambiguity for :core-network

The build is failing because the `:core-network` module defines product flavors in the `environment` dimension (`local` and `cloud`), but consumer modules like `:data` and several feature modules do not define this dimension. Gradle cannot automatically choose which flavor of `:core-network` to use for these modules.

## Proposed Changes

I will add `missingDimensionStrategy` to the `defaultConfig` of all modules that depend on `:core-network` but do not have the `environment` dimension. This will instruct Gradle to pick a default flavor (preferring `cloud` then `local`) when resolving the `:core-network` dependency.

### Core Modules

#### [MODIFY] [data/build.gradle.kts](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Feature/Android-AI-Assistant-Test/data/build.gradle.kts)
- Add `missingDimensionStrategy("environment", "cloud", "local")` to `defaultConfig`.

### Feature Modules

#### [MODIFY] [feature-chat/build.gradle.kts](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Feature/Android-AI-Assistant-Test/feature-chat/build.gradle.kts)
- Add `missingDimensionStrategy("environment", "cloud", "local")` to `defaultConfig`.

#### [MODIFY] [feature-history/build.gradle.kts](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Feature/Android-AI-Assistant-Test/feature-history/build.gradle.kts)
- Add `missingDimensionStrategy("environment", "cloud", "local")` to `defaultConfig`.

#### [MODIFY] [feature-rag/build.gradle.kts](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Feature/Android-AI-Assistant-Test/feature-rag/build.gradle.kts)
- Add `missingDimensionStrategy("environment", "cloud", "local")` to `defaultConfig`.

#### [MODIFY] [feature-translator/build.gradle.kts](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Feature/Android-AI-Assistant-Test/feature-translator/build.gradle.kts)
- Add `missingDimensionStrategy("environment", "cloud", "local")` to `defaultConfig`.

## Verification Plan

### Automated Tests
- Run `:data:assembleDebug` to verify the main build error is resolved.
- Run `:feature-chat:assembleDebug` to verify other modules are also fixed.
- Run `:app:assembleDebug` to ensure overall project build still works.
