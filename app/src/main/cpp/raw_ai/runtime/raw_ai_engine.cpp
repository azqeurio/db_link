#include "raw_ai_engine.h"
#include "../contract/model_manifest.h"
#include <fstream>
#include <chrono>
#include <cstring>
#include <algorithm>

namespace raw_ai {

static bool readBinaryFloatFile(const std::string& path, std::vector<float>& outData) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    std::streamsize size = f.tellg();
    f.seekg(0, std::ios::beg);
    if (size % sizeof(float) != 0) return false;
    outData.resize(size / sizeof(float));
    return f.read(reinterpret_cast<char*>(outData.data()), size) ? true : false;
}

static bool writeBinaryFloatFile(const std::string& path, const std::vector<float>& data) {
    std::ofstream f(path, std::ios::binary);
    if (!f) return false;
    return f.write(reinterpret_cast<const char*>(data.data()), data.size() * sizeof(float)) ? true : false;
}

std::unique_ptr<RawAiEngine> RawAiEngine::create(
    const std::string& modelPath,
    const std::string& manifestJson,
    std::string* error) {

    auto engine = std::make_unique<RawAiEngine>();
    if (engine->initialize(modelPath, manifestJson, error)) {
        return engine;
    }
    return nullptr;
}

RawAiEngine::RawAiEngine() : isCancelled_(false), isInitialized_(false) {}

RawAiEngine::~RawAiEngine() {}

bool RawAiEngine::initialize(
    const std::string& modelPath,
    const std::string& manifestJson,
    std::string* error) {

    isCancelled_ = false;

    // 1. Parse manifest configurations
    if (!parseModelManifest(manifestJson, config_, error)) {
        return false;
    }

    // 2. Override default reference path with the exact local path provided
    config_.cpuModelPath = modelPath;

    // 3. Define target fallback priority
    // PR 2 starts with CPU reference compilation
    BackendPolicy policy;
    policy.priority = { BackendType::Cpu };

    // 4. Instantiate and test chosen backend
    backend_ = BackendFactory::createWithFallback(policy, config_, paths_, &backendDiag_, error);
    if (!backend_) {
        return false;
    }

    isInitialized_ = true;
    return true;
}

bool RawAiEngine::processSingleTile(
    const std::string& inputPath,
    const std::string& outputPath,
    float iso,
    BackendType preferredBackend,
    EngineDiagnostics* diag,
    std::string* error) {

    if (!isInitialized_ || !backend_) {
        if (error) *error = "Engine backend not initialized";
        return false;
    }

    if (isCancelled_) {
        if (error) *error = "Operation cancelled";
        return false;
    }

    auto startProc = std::chrono::high_resolution_clock::now();

    // 1. Read binary float input tile
    std::vector<float> inputData;
    if (!readBinaryFloatFile(inputPath, inputData)) {
        if (error) *error = "Failed to read binary input file: " + inputPath;
        return false;
    }

    // Input size verification: 256x256x3 = 196608 floats
    size_t expectedInSize = config_.imageInput.shape[1] * config_.imageInput.shape[2] * config_.imageInput.shape[3];
    if (inputData.size() < expectedInSize) {
        if (error) *error = "Input data size mismatch. Got: " + std::to_string(inputData.size()) + ", Expected: " + std::to_string(expectedInSize);
        return false;
    }

    // 2. Match the documented training/runtime condition contract.
    const float boundedIso = std::min(std::max(iso, 0.0f), 65535.0f);
    std::vector<float> condData = { boundedIso / 6400.0f };

    // 3. Prepare output float vector: 3x256x256 = 196608 floats
    size_t expectedOutSize = config_.imageOutput.shape[1] * config_.imageOutput.shape[2] * config_.imageOutput.shape[3];
    std::vector<float> outputData(expectedOutSize, 0.0f);

    // 4. Run inference
    RunStats runStats;
    if (!backend_->run(inputData, condData, outputData, &runStats, error)) {
        return false;
    }

    // 5. Write binary float output tile
    if (!writeBinaryFloatFile(outputPath, outputData)) {
        if (error) *error = "Failed to write binary output file: " + outputPath;
        return false;
    }

    auto endProc = std::chrono::high_resolution_clock::now();
    double procMs = std::chrono::duration<double, std::milli>(endProc - startProc).count();

    if (diag) {
        diag->selectedBackend = backend_->name();
        diag->fallbackReason = backendDiag_.fallbackReason;
        diag->initializationMs = backendDiag_.initializationMs;
        diag->processingMs = procMs;
        diag->peakMemoryBytes = 0; // Handled at application layer
    }

    return true;
}

void RawAiEngine::cancel() {
    isCancelled_ = true;
}

} // namespace raw_ai
