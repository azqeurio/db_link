#pragma once

#include "../backends/inference_backend.h"
#include "backend_factory.h"
#include <string>
#include <memory>
#include <atomic>

namespace raw_ai {

struct EngineDiagnostics {
    std::string selectedBackend;
    std::string fallbackReason;
    double initializationMs = 0.0;
    double processingMs = 0.0;
    long peakMemoryBytes = 0;
};

class RawAiEngine {
public:
    static std::unique_ptr<RawAiEngine> create(
        const std::string& modelPath,
        const std::string& manifestJson,
        std::string* error);

    RawAiEngine();
    ~RawAiEngine();

    bool initialize(
        const std::string& modelPath,
        const std::string& manifestJson,
        std::string* error);

    // Denoises single tile directly (to be expanded in PR 3 for streaming tiler)
    bool processSingleTile(
        const std::string& inputPath,
        const std::string& outputPath,
        float iso,
        BackendType preferredBackend,
        EngineDiagnostics* diag,
        std::string* error);

    void cancel();

private:
    ModelConfig config_;
    RuntimePaths paths_;

    std::unique_ptr<InferenceBackend> backend_;
    BackendDiagnostics backendDiag_;

    std::atomic<bool> isCancelled_;
    bool isInitialized_;
};

} // namespace raw_ai
