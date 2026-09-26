/// On-device AI service abstraction.
///
/// This defines the interface and state machine only.
/// The actual model implementation (e.g. Gemma GGUF via llama.cpp FFI)
/// is added in a later phase once the rest of the stack is stable.
///
/// When on-device AI is active, NO network inference request is made.
/// This contract must be enforced by the [ChatController].
library;

/// Lifecycle state of the on-device AI service.
enum OnDeviceAiState {
  /// Device capability has not been checked yet.
  checking,

  /// Hardware/OS requirements are not met (e.g. <4GB RAM, unsupported arch).
  unsupported,

  /// Device is capable but no model is loaded.
  supported,

  /// A model file is being downloaded or extracted.
  loadingModel,

  /// Model is loaded and ready to accept inference requests.
  ready,

  /// Inference is in progress.
  running,

  /// An error occurred (see [OnDeviceAiService.lastError]).
  error,
}

/// Interface that any on-device inference backend must implement.
abstract class OnDeviceAiService {
  /// Current lifecycle state.
  OnDeviceAiState get state;

  /// Human-readable description of the last error, or null.
  String? get lastError;

  /// Whether this device meets the minimum hardware requirements.
  Future<bool> checkSupport();

  /// Load the model from local storage. Emits [OnDeviceAiState.loadingModel]
  /// then [OnDeviceAiState.ready] on success.
  Future<void> loadModel();

  /// Run inference on [prompt]. Returns a stream of token strings.
  ///
  /// Throws [UnsupportedError] if [state] != [OnDeviceAiState.ready].
  /// MUST NOT make any network request.
  Stream<String> generate(String prompt);

  /// Release model memory.
  Future<void> unloadModel();
}

/// Stub implementation — returned when on-device AI is not yet supported
/// by this build. Used as the default provider in the DI graph.
class StubOnDeviceAiService implements OnDeviceAiService {
  @override
  OnDeviceAiState get state => OnDeviceAiState.unsupported;

  @override
  String? get lastError => 'On-device inference is not available in this build.';

  @override
  Future<bool> checkSupport() async => false;

  @override
  Future<void> loadModel() async {}

  @override
  Stream<String> generate(String prompt) {
    throw UnsupportedError('On-device AI is not available in this build.');
  }

  @override
  Future<void> unloadModel() async {}
}
