#pragma once

#include <string>
#include <vector>
#include <span>
#include <memory>

enum class BackendType {
    QualcommNpu = 0,
    Gpu = 1,
    Cpu = 2,
    Disabled = 3
};

struct BackendDiagnostics {
    BackendType requestedBackend = BackendType::Cpu;
    BackendType selectedBackend = BackendType::Cpu;

    std::string acceleratorName;
    std::string modelHash;
    std::string runtimeVersion;
    std::string compilationMode;
    std::string fallbackReason;

    double initializationMs = 0.0;
    double firstInferenceMs = 0.0;
    double lastInferenceMs = 0.0;

    int compiledPartitionCount = 0;
    int unsupportedOperatorCount = 0;
};

struct RunStats {
    double inputCopyMs = 0.0;
    double inferenceMs = 0.0;
    double outputCopyMs = 0.0;
};

// ModelConfig contains settings parsed from model_manifest.json
struct ModelConfig {
    std::string modelDomain;
    std::string cpuModelPath;
    std::string fp16ModelPath;

    struct TensorConfig {
        std::string name;
        int index = 0;
        std::vector<int> shape;
        std::string layout; // NHWC or NCHW
        std::string dtype;
    } imageInput, conditionInput, imageOutput;

    std::string formula; // condition value formula
};

struct RuntimePaths {
    std::string internalCacheDir;
};

class InferenceBackend {
public:
    virtual ~InferenceBackend() = default;

    virtual bool initialize(
        const ModelConfig& config,
        const RuntimePaths& paths,
        BackendDiagnostics* diagnostics,
        std::string* error) = 0;

    virtual bool run(
        std::span<const float> imageInput,
        std::span<const float> conditionInput,
        std::span<float> imageOutput,
        RunStats* stats,
        std::string* error) = 0;

    virtual BackendType type() const = 0;
    virtual const char* name() const = 0;
};
