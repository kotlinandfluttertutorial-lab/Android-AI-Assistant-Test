# Implementation Plan - Fix Pre-Push Check Failures

This plan addresses the 7 failing checks reported by `pre-push-check.ps1`, focusing on Android compilation errors, architecture violations, style issues, and backend test failures.

## User Review Required

> [!IMPORTANT]
> The primary fix involves moving AI interfaces from `:core-ai` to `:core-common`. This is necessary because the `:domain` module currently contains use cases that depend on these interfaces, but `:domain` is architecturally prohibited from depending on `:core-ai`. Moving them to `:core-common` allows both modules to share these contracts without violating dependency rules.

## Proposed Changes

### [Android] Architecture & Compilation Fixes

#### [MODIFY] [ApiResult.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/core-common/src/main/kotlin/com/aiassistant/core/common/ApiResult.kt)
- Add RAG-related data classes and interfaces moved from `:core-ai`.

#### [NEW] [RagContracts.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/core-common/src/main/kotlin/com/aiassistant/core/common/RagContracts.kt)
- Contains `OnDeviceInferenceEngine`, `OnDeviceEmbeddingModel`, `LocalVectorIndex` (interface), `HardwareAccelerator`, `OnDeviceStreamEvent`, `BenchmarkResult`, `ModelLoadEvent`, and `Chunker`.

#### [MODIFY] [OnDeviceInferenceEngine.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/core-ai/src/main/kotlin/com/aiassistant/core/ai/ondevicerag/OnDeviceInferenceEngine.kt)
- Remove interface and supporting types (now in `:core-common`).
- Update `MediaPipeInferenceEngine` to implement the interface from `:core-common`.
- Fix imports.

#### [MODIFY] [OnDeviceEmbeddingModel.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/core-ai/src/main/kotlin/com/aiassistant/core/ai/ondevicerag/OnDeviceEmbeddingModel.kt)
- Remove interface and `ModelLoadEvent`.
- Update implementation and imports.

#### [MODIFY] [LocalVectorIndex.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/core-ai/src/main/kotlin/com/aiassistant/core/ai/ondevicerag/LocalVectorIndex.kt)
- Rename existing class to `LocalVectorIndexImpl`.
- Implement `LocalVectorIndex` interface from `:core-common`.
- Fix imports.

#### [DELETE] [Chunker.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/core-ai/src/main/kotlin/com/aiassistant/core/ai/ondevicerag/Chunker.kt)
- Component moved to `:core-common`.

#### [MODIFY] Use Cases in `:domain`
- [BenchmarkOnDeviceUseCase.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/domain/src/main/kotlin/com/aiassistant/domain/usecase/ondevicerag/BenchmarkOnDeviceUseCase.kt)
- [DeleteOnDeviceDocumentUseCase.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/domain/src/main/kotlin/com/aiassistant/domain/usecase/ondevicerag/DeleteOnDeviceDocumentUseCase.kt)
- [OnDeviceIngestDocumentUseCase.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/domain/src/main/kotlin/com/aiassistant/domain/usecase/ondevicerag/OnDeviceIngestDocumentUseCase.kt)
- [OnDeviceQueryUseCase.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/domain/src/main/kotlin/com/aiassistant/domain/usecase/ondevicerag/OnDeviceQueryUseCase.kt)
- [RouteQueryUseCase.kt](file:///J:/Android/AndroidStudioProjects/Kiro/TestBranch/Develop_Main/Android-AI-Assistant-Test/domain/src/main/kotlin/com/aiassistant/domain/usecase/ondevicerag/RouteQueryUseCase.kt)
- Update imports to `:core-common`.

### [Android] Style & Lint Fixes

- Fix `LongMethod`, `CognitiveComplexMethod`, and `MagicNumber` issues in the RAG code by extracting helper methods and defining constants.
- Remove unused imports and fix indentation as reported by `ktlint`.

### [Backend] Test & Dependency Fixes

- Investigate and fix the `test_4a_estimated_tokens_below_provider_max_after_build_prompt` timeout by mocking `OpenAIClient` properly in `conftest.py` or the test file itself.
- Resolve the `SECRET_KEY` env var validation failure in integration tests.
- Update `requirements.txt` to fix `pip-audit` issues if network allows, or document the workaround.

## Verification Plan

### Automated Tests
- Run `./gradlew :domain:compileDebugKotlin` and `./gradlew :core-ai:compileDebugKotlin`.
- Run `./gradlew :domain:testDebugUnitTest` and `./gradlew :core-ai:testDebugUnitTest`.
- Run `./gradlew ktlintCheck detekt`.
- Run `pytest backend/tests` to verify backend fixes.

### Manual Verification
- Verify the `pre-push-check.ps1` script passes locally.
