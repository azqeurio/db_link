#include "model_manifest.h"
#include <sstream>
#include <algorithm>

namespace raw_ai {

// Helper to extract a string value for a key in JSON (simple non-nested/nested key match)
static std::string extractJsonString(const std::string& json, const std::string& key) {
    size_t keyPos = json.find("\"" + key + "\"");
    if (keyPos == std::string::npos) return "";

    size_t colonPos = json.find(":", keyPos);
    if (colonPos == std::string::npos) return "";

    size_t quoteStart = json.find("\"", colonPos);
    if (quoteStart == std::string::npos) return "";

    size_t quoteEnd = json.find("\"", quoteStart + 1);
    if (quoteEnd == std::string::npos) return "";

    return json.substr(quoteStart + 1, quoteEnd - quoteStart - 1);
}

// Helper to extract an integer value
static int extractJsonInt(const std::string& json, const std::string& key) {
    size_t keyPos = json.find("\"" + key + "\"");
    if (keyPos == std::string::npos) return 0;

    size_t colonPos = json.find(":", keyPos);
    if (colonPos == std::string::npos) return 0;

    // Find first digit or minus
    size_t valPos = json.find_first_of("-0123456789", colonPos);
    if (valPos == std::string::npos) return 0;

    size_t valEnd = json.find_first_not_of("-0123456789", valPos);
    std::string valStr = (valEnd == std::string::npos) ? json.substr(valPos) : json.substr(valPos, valEnd - valPos);

    return std::stoi(valStr);
}

// Helper to extract a 1D array of integers (e.g. shapes like [1, 256, 256, 3])
static std::vector<int> extractJsonIntArray(const std::string& json, const std::string& key, size_t searchStartPos = 0) {
    std::vector<int> result;
    size_t keyPos = json.find("\"" + key + "\"", searchStartPos);
    if (keyPos == std::string::npos) return result;

    size_t bracketStart = json.find("[", keyPos);
    if (bracketStart == std::string::npos) return result;

    size_t bracketEnd = json.find("]", bracketStart);
    if (bracketEnd == std::string::npos) return result;

    std::string arrayContent = json.substr(bracketStart + 1, bracketEnd - bracketStart - 1);
    std::stringstream ss(arrayContent);
    std::string item;
    while (std::getline(ss, item, ',')) {
        // Trim spaces
        item.erase(std::remove_if(item.begin(), item.end(), isspace), item.end());
        if (!item.empty()) {
            result.push_back(std::stoi(item));
        }
    }
    return result;
}

bool parseModelManifest(const std::string& manifestJson, ModelConfig& outConfig, std::string* error) {
    try {
        outConfig.modelDomain = extractJsonString(manifestJson, "model_domain");
        if (outConfig.modelDomain.empty()) {
            if (error) *error = "Failed to parse model_domain from manifest";
            return false;
        }

        outConfig.cpuModelPath = extractJsonString(manifestJson, "path"); // Will match the first path

        // Find CPU reference path section
        size_t cpuRefPos = manifestJson.find("\"cpu_reference\"");
        if (cpuRefPos != std::string::npos) {
            outConfig.cpuModelPath = extractJsonString(manifestJson.substr(cpuRefPos), "path");
        }

        // Find Accelerated path section
        size_t accPos = manifestJson.find("\"accelerated\"");
        if (accPos != std::string::npos) {
            outConfig.fp16ModelPath = extractJsonString(manifestJson.substr(accPos), "path");
        }

        // 1. Parse Image Input Tensors
        size_t imgInpPos = manifestJson.find("\"image_input\"");
        if (imgInpPos != std::string::npos) {
            std::string block = manifestJson.substr(imgInpPos, 500); // lookahead limit
            outConfig.imageInput.name = extractJsonString(block, "tensor_name");
            outConfig.imageInput.index = extractJsonInt(block, "tensor_index");
            outConfig.imageInput.shape = extractJsonIntArray(manifestJson, "shape", imgInpPos);
            outConfig.imageInput.layout = extractJsonString(block, "layout");
            outConfig.imageInput.dtype = extractJsonString(block, "dtype");
        } else {
            if (error) *error = "Missing image_input description";
            return false;
        }

        // 2. Parse Condition Input Tensors
        size_t condInpPos = manifestJson.find("\"condition_input\"");
        if (condInpPos != std::string::npos) {
            std::string block = manifestJson.substr(condInpPos, 500);
            outConfig.conditionInput.name = extractJsonString(block, "tensor_name");
            outConfig.conditionInput.index = extractJsonInt(block, "tensor_index");
            outConfig.conditionInput.shape = extractJsonIntArray(manifestJson, "shape", condInpPos);
            outConfig.conditionInput.layout = "UNKNOWN"; // Usually 2D or scalar
            outConfig.conditionInput.dtype = extractJsonString(block, "dtype");
            outConfig.formula = extractJsonString(block, "formula");
        } else {
            if (error) *error = "Missing condition_input description";
            return false;
        }

        // 3. Parse Image Output Tensors
        size_t imgOutPos = manifestJson.find("\"image_output\"");
        if (imgOutPos != std::string::npos) {
            std::string block = manifestJson.substr(imgOutPos, 500);
            outConfig.imageOutput.name = extractJsonString(block, "tensor_name");
            outConfig.imageOutput.index = extractJsonInt(block, "tensor_index");
            outConfig.imageOutput.shape = extractJsonIntArray(manifestJson, "shape", imgOutPos);
            outConfig.imageOutput.layout = extractJsonString(block, "layout");
            outConfig.imageOutput.dtype = extractJsonString(block, "dtype");
        } else {
            if (error) *error = "Missing image_output description";
            return false;
        }

        return true;
    } catch (const std::exception& e) {
        if (error) *error = std::string("Manifest parse exception: ") + e.what();
        return false;
    }
}

} // namespace raw_ai
