#pragma once

#include "../backends/inference_backend.h"
#include <memory>
#include <vector>

namespace raw_ai {

struct BackendPolicy {
    std::vector<BackendType> priority;
};

class BackendFactory {
public:
    static std::unique_ptr<InferenceBackend> create(BackendType type);

    static std::unique_ptr<InferenceBackend> createWithFallback(
        const BackendPolicy& policy,
        const ModelConfig& config,
        const RuntimePaths& paths,
        BackendDiagnostics* diagnostics,
        std::string* error);

private:
    static bool initializeAndSelfTest(
        InferenceBackend& backend,
        const ModelConfig& config,
        const RuntimePaths& paths,
        BackendDiagnostics* diagnostics,
        std::string* error);
};

} // namespace raw_ai
