#include "backend_factory.h"
#include "../backends/litert_compiled_backend.h"

namespace raw_ai {

static const char* backendTypeToString(BackendType type) {
    switch (type) {
        case BackendType::QualcommNpu: return "Qualcomm_NPU";
        case BackendType::Gpu: return "GPU";
        case BackendType::Cpu: return "CPU";
        default: return "Disabled";
    }
}

std::unique_ptr<InferenceBackend> BackendFactory::create(BackendType type) {
    switch (type) {
        case BackendType::Cpu:
            return std::make_unique<LiteRtCompiledBackend>(BackendType::Cpu);
        case BackendType::Gpu:
            return std::make_unique<LiteRtCompiledBackend>(BackendType::Gpu);
        case BackendType::QualcommNpu:
            // Will be implemented in Milestone 5
            return nullptr;
        default:
            return nullptr;
    }
}

std::unique_ptr<InferenceBackend> BackendFactory::createWithFallback(
    const BackendPolicy& policy,
    const ModelConfig& config,
    const RuntimePaths& paths,
    BackendDiagnostics* diagnostics,
    std::string* error) {

    std::string accumulatedErrors;

    for (BackendType candidate : policy.priority) {
        auto backend = create(candidate);
        if (!backend) {
            accumulatedErrors += std::string(backendTypeToString(candidate)) + " creation failed; ";
            continue;
        }

        std::string initError;
        if (initializeAndSelfTest(*backend, config, paths, diagnostics, &initError)) {
            return backend;
        } else {
            accumulatedErrors += std::string(backend->name()) + " init failed: " + initError + "; ";
            if (diagnostics) {
                diagnostics->fallbackReason = accumulatedErrors;
            }
        }
    }

    if (error) *error = "All backends failed. Details: " + accumulatedErrors;
    return nullptr;
}

bool BackendFactory::initializeAndSelfTest(
    InferenceBackend& backend,
    const ModelConfig& config,
    const RuntimePaths& paths,
    BackendDiagnostics* diagnostics,
    std::string* error) {

    return backend.initialize(config, paths, diagnostics, error);
}

} // namespace raw_ai
