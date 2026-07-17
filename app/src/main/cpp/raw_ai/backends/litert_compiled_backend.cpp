#include "litert_compiled_backend.h"
#include "tensorflow/lite/model.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"
#include "tensorflow/lite/delegates/gpu/delegate.h"
#include <chrono>
#include <cstring>

namespace {
bool hasShape(const TfLiteTensor* tensor, std::initializer_list<int> expected) {
    if (!tensor || !tensor->dims || tensor->dims->size != static_cast<int>(expected.size())) return false;
    int position = 0;
    for (int value : expected) {
        if (tensor->dims->data[position++] != value) return false;
    }
    return true;
}
}

LiteRtCompiledBackend::LiteRtCompiledBackend(BackendType type) : type_(type) {
    worker_.start();
}

LiteRtCompiledBackend::~LiteRtCompiledBackend() {
    // Stop the serial worker and release delegates inside the worker context
    worker_.post([this]() {
        interpreter_.reset();
        model_.reset();
        if (gpuDelegate_) {
            TfLiteGpuDelegateV2Delete(gpuDelegate_);
            gpuDelegate_ = nullptr;
        }
    });
    worker_.wait();
    worker_.stop();
}

BackendType LiteRtCompiledBackend::type() const {
    return type_;
}

const char* LiteRtCompiledBackend::name() const {
    switch (type_) {
        case BackendType::Cpu: return "LiteRT_CPU";
        case BackendType::Gpu: return "LiteRT_GPU";
        default: return "LiteRT_Unknown";
    }
}

bool LiteRtCompiledBackend::initialize(
    const ModelConfig& config,
    const RuntimePaths& paths,
    BackendDiagnostics* diagnostics,
    std::string* error) {

    bool success = false;
    worker_.post([this, &config, &paths, diagnostics, error, &success]() {
        success = initializeOnWorker(config, paths, diagnostics, error);
    });
    worker_.wait();
    return success;
}

bool LiteRtCompiledBackend::run(
    std::span<const float> imageInput,
    std::span<const float> conditionInput,
    std::span<float> imageOutput,
    RunStats* stats,
    std::string* error) {

    if (!isInitialized_) {
        if (error) *error = "Backend not initialized";
        return false;
    }

    bool success = false;
    worker_.post([this, imageInput, conditionInput, imageOutput, stats, error, &success]() {
        success = runOnWorker(imageInput, conditionInput, imageOutput, stats, error);
    });
    worker_.wait();
    return success;
}

bool LiteRtCompiledBackend::initializeOnWorker(
    const ModelConfig& config,
    const RuntimePaths& paths,
    BackendDiagnostics* diagnostics,
    std::string* error) {

    auto startInit = std::chrono::high_resolution_clock::now();
    config_ = config;

    std::string modelPath = (type_ == BackendType::Gpu) ? config.fp16ModelPath : config.cpuModelPath;
    if (modelPath.empty()) {
        modelPath = config.cpuModelPath; // Fallback path string
    }

    // 1. Build Flatbuffer Model
    model_ = tflite::FlatBufferModel::BuildFromFile(modelPath.c_str());
    if (!model_) {
        if (error) *error = "Failed to load model file: " + modelPath;
        return false;
    }

    // 2. Create Interpreter
    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder builder(*model_, resolver);
    if (builder(&interpreter_) != kTfLiteOk || !interpreter_) {
        if (error) *error = "Failed to build TFLite interpreter";
        return false;
    }

    // 3. Inject GPU Delegate if requested
    if (type_ == BackendType::Gpu) {
        TfLiteGpuDelegateOptionsV2 gpuOptions = TfLiteGpuDelegateOptionsV2Default();
        gpuOptions.is_precision_loss_allowed = 1; // Allow float16 precision
        gpuOptions.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_SUSTAINED_SPEED;

        gpuDelegate_ = TfLiteGpuDelegateV2Create(&gpuOptions);
        if (gpuDelegate_ == nullptr) {
            if (error) *error = "Failed to create GPU Delegate";
            return false;
        }

        if (interpreter_->ModifyGraphWithDelegate(gpuDelegate_) != kTfLiteOk) {
            if (error) *error = "Failed to link GPU Delegate to graph";
            TfLiteGpuDelegateV2Delete(gpuDelegate_);
            gpuDelegate_ = nullptr;
            return false;
        }
    }

    // 4. Allocate Tensors
    if (interpreter_->AllocateTensors() != kTfLiteOk) {
        if (error) *error = "Failed to allocate TFLite tensors";
        return false;
    }

    // 5. Resolve the stable public contract by name. Tensor indices differ by model graph.
    for (int index : interpreter_->inputs()) {
        const TfLiteTensor* tensor = interpreter_->tensor(index);
        if (tensor && tensor->name && std::strcmp(tensor->name, "input") == 0) imageInputIndex_ = index;
        if (tensor && tensor->name && std::strcmp(tensor->name, "cond") == 0) conditionInputIndex_ = index;
    }
    for (int index : interpreter_->outputs()) {
        const TfLiteTensor* tensor = interpreter_->tensor(index);
        if (tensor && tensor->name && std::strcmp(tensor->name, "output") == 0) imageOutputIndex_ = index;
    }
    const TfLiteTensor* inputTensor = interpreter_->tensor(imageInputIndex_);
    const TfLiteTensor* conditionTensor = interpreter_->tensor(conditionInputIndex_);
    const TfLiteTensor* outputTensor = interpreter_->tensor(imageOutputIndex_);
    if (!hasShape(inputTensor, {1, 256, 256, 3}) || inputTensor->type != kTfLiteFloat32 ||
        !hasShape(conditionTensor, {1, 1}) || conditionTensor->type != kTfLiteFloat32 ||
        !hasShape(outputTensor, {1, 3, 256, 256}) || outputTensor->type != kTfLiteFloat32) {
        if (error) *error = "Model tensor contract mismatch: expected input NHWC float32, cond float32, output NCHW float32";
        return false;
    }

    // 6. Warmup Run (3 iterations)
    for (int i = 0; i < 3; ++i) {
        // Zero out inputs for warmup safely
        float* inputData = interpreter_->typed_tensor<float>(imageInputIndex_);
        if (inputData) {
            size_t bytes = inputTensor->bytes;
            std::memset(inputData, 0, bytes);
        }

        float* condData = interpreter_->typed_tensor<float>(conditionInputIndex_);
        if (condData) {
            *condData = 0.0f;
        }

        auto startWarmupInf = std::chrono::high_resolution_clock::now();
        if (interpreter_->Invoke() != kTfLiteOk) {
            if (error) *error = "Warmup invoke failed";
            return false;
        }
        auto endWarmupInf = std::chrono::high_resolution_clock::now();
        double infMs = std::chrono::duration<double, std::milli>(endWarmupInf - startWarmupInf).count();
        if (i == 0 && diagnostics) {
            diagnostics->firstInferenceMs = infMs;
        }
        if (diagnostics) {
            diagnostics->lastInferenceMs = infMs;
        }
    }

    auto endInit = std::chrono::high_resolution_clock::now();
    double initMs = std::chrono::duration<double, std::milli>(endInit - startInit).count();

    if (diagnostics) {
        diagnostics->initializationMs = initMs;
        diagnostics->selectedBackend = type_;
        diagnostics->requestedBackend = type_;
        diagnostics->acceleratorName = (type_ == BackendType::Gpu) ? "GPU (Adreno)" : "CPU";
    }

    isInitialized_ = true;
    return true;
}

bool LiteRtCompiledBackend::runOnWorker(
    std::span<const float> imageInput,
    std::span<const float> conditionInput,
    std::span<float> imageOutput,
    RunStats* stats,
    std::string* error) {

    auto startCopyIn = std::chrono::high_resolution_clock::now();

    // 1. Copy Image Input Data
    int inputIdx = imageInputIndex_;
    float* inputTensorData = interpreter_->typed_tensor<float>(inputIdx);
    if (!inputTensorData) {
        if (error) *error = "Input tensor pointer is null";
        return false;
    }

    TfLiteTensor* inputTensor = interpreter_->tensor(inputIdx);
    size_t inputSizeElements = inputTensor->bytes / sizeof(float);
    if (imageInput.size() < inputSizeElements) {
        if (error) *error = "Image input span size is smaller than tensor size";
        return false;
    }
    std::copy(imageInput.begin(), imageInput.begin() + inputSizeElements, inputTensorData);

    // 2. Copy Condition Input Data
    int condIdx = conditionInputIndex_;
    float* condTensorData = interpreter_->typed_tensor<float>(condIdx);
    if (condTensorData && !conditionInput.empty()) {
        *condTensorData = conditionInput[0];
    }

    auto startInference = std::chrono::high_resolution_clock::now();
    stats->inputCopyMs = std::chrono::duration<double, std::milli>(startInference - startCopyIn).count();

    // 3. Invoke Inference
    if (interpreter_->Invoke() != kTfLiteOk) {
        if (error) *error = "Inference execution failed";
        return false;
    }

    auto startCopyOut = std::chrono::high_resolution_clock::now();
    stats->inferenceMs = std::chrono::duration<double, std::milli>(startCopyOut - startInference).count();

    // 4. Copy Image Output Data
    int outputIdx = imageOutputIndex_;
    const float* outputTensorData = interpreter_->typed_tensor<float>(outputIdx);
    if (!outputTensorData) {
        if (error) *error = "Output tensor pointer is null";
        return false;
    }

    TfLiteTensor* outputTensor = interpreter_->tensor(outputIdx);
    size_t outputSizeElements = outputTensor->bytes / sizeof(float);
    if (imageOutput.size() < outputSizeElements) {
        if (error) *error = "Image output span size is smaller than tensor size";
        return false;
    }
    std::copy(outputTensorData, outputTensorData + outputSizeElements, imageOutput.begin());

    auto endCopyOut = std::chrono::high_resolution_clock::now();
    stats->outputCopyMs = std::chrono::duration<double, std::milli>(endCopyOut - startCopyOut).count();

    return true;
}
