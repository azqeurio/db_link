#pragma once

#include "inference_backend.h"
#include "../runtime/serial_worker.h"

// Forward declarations of TensorFlow Lite classes
namespace tflite {
    class FlatBufferModel;
    class Interpreter;
}
typedef struct TfLiteDelegate TfLiteDelegate;

class LiteRtCompiledBackend final : public InferenceBackend {
public:
    explicit LiteRtCompiledBackend(BackendType type);
    ~LiteRtCompiledBackend() override;

    bool initialize(
        const ModelConfig& config,
        const RuntimePaths& paths,
        BackendDiagnostics* diagnostics,
        std::string* error) override;

    bool run(
        std::span<const float> imageInput,
        std::span<const float> conditionInput,
        std::span<float> imageOutput,
        RunStats* stats,
        std::string* error) override;

    BackendType type() const override;
    const char* name() const override;

private:
    bool initializeOnWorker(
        const ModelConfig& config,
        const RuntimePaths& paths,
        BackendDiagnostics* diagnostics,
        std::string* error);

    bool runOnWorker(
        std::span<const float> imageInput,
        std::span<const float> conditionInput,
        std::span<float> imageOutput,
        RunStats* stats,
        std::string* error);

    BackendType type_;
    raw_ai::SerialWorker worker_;

    // TFLite core handles
    std::unique_ptr<tflite::FlatBufferModel> model_;
    std::unique_ptr<tflite::Interpreter> interpreter_;
    TfLiteDelegate* gpuDelegate_ = nullptr;

    // Model configuration copy
    ModelConfig config_;
    int imageInputIndex_ = -1;
    int conditionInputIndex_ = -1;
    int imageOutputIndex_ = -1;

    // Cache diagnostic statistics
    bool isInitialized_ = false;
};
