#pragma once

#include "../backends/inference_backend.h"
#include <string>

namespace raw_ai {

// Parses the manifest JSON string into a ModelConfig structure
bool parseModelManifest(const std::string& manifestJson, ModelConfig& outConfig, std::string* error);

} // namespace raw_ai
