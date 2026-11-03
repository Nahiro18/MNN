#include "processor.h"

#include <algorithm>
#include <regex>
#include <set>
#include <utility>

#include <cv/imgcodecs.hpp>

#include "mls_log.h"
#include "video/video_processor.h"

namespace mls {
PromptProcessor::PromptProcessor(PromptProcessorConfig config)
    : config_(std::move(config)) {
    config_.video_processor_config.max_debug_images = config_.max_debug_images;
    config_.video_processor_config.save_first_image = config_.save_first_image;
    config_.video_processor_config.max_frames = std::max(config_.video_processor_config.max_frames,
                                                         config_.max_debug_images);
}

MNN::Express::VARP PromptProcessor::LoadImageFromPath(const std::string& image_path) {
    // imread yields the uint8 BGR HWC var that Omni::visionProcess expects, and logs + returns
    // nullptr on failure. Leaving this unimplemented made every attached image silently degrade
    // the prompt to text-only, which the model answers with degenerate tokens.
    return MNN::CV::imread(image_path);
}

std::string PromptProcessor::EscapeForRegex(const std::string& text) {
    std::string escaped;
    escaped.reserve(text.size() * 2);
    for (char c : text) {
        switch (c) {
            case '.':
            case '^':
            case '$':
            case '|':
            case '(': 
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
            case '*':
            case '+':
            case '?':
            case '\\':
                escaped.push_back('\\');
                break;
            default:
                break;
        }
        escaped.push_back(c);
    }
    return escaped;
}

PromptProcessingResult PromptProcessor::Process(const std::string& prompt_text) const {
    PromptProcessingResult result;
    result.multimodal_prompt.prompt_template = prompt_text;

    ProcessorState state;
    state.final_prompt = prompt_text;

    if (prompt_text.find("<img>") == std::string::npos && prompt_text.find("<video>") == std::string::npos) {
        return result;
    }

    bool has_images = HandleImageTags(prompt_text, result, state);
    bool has_videos = HandleVideoTags(prompt_text, result, state);
    (void)has_images;
    (void)has_videos;

    result.had_multimodal_markers =
        prompt_text.find("<img>") != std::string::npos || prompt_text.find("<video>") != std::string::npos;
    result.has_multimodal = result.had_multimodal_markers && (state.successful_loads > 0);

    // Drop any image/video tag that has no decoded part behind it (for example an empty
    // <img></img> or a tag skipped after hitting the image limit). Leaving such a tag in
    // the prompt makes Omni try to load it again and fail the whole request.
    {
        std::regex media_regex("<(img|video)>([^<]*)</\\1>");
        std::set<std::string> unresolved;
        for (std::sregex_iterator it(prompt_text.begin(), prompt_text.end(), media_regex), end;
             it != end; ++it) {
            const std::string content = (*it)[2].str();
            if (result.multimodal_prompt.images.find(content) == result.multimodal_prompt.images.end() &&
                result.multimodal_prompt.videos.find(content) == result.multimodal_prompt.videos.end()) {
                unresolved.insert(content);
            }
        }
        for (const auto& content : unresolved) {
            const std::string escaped = EscapeForRegex(content);
            state.final_prompt = std::regex_replace(
                state.final_prompt, std::regex("<img>" + escaped + "</img>"), "");
            state.final_prompt = std::regex_replace(
                state.final_prompt, std::regex("<video>" + escaped + "</video>"), "");
        }
    }
    result.multimodal_prompt.prompt_template = state.final_prompt;

    if (result.has_multimodal) {
        MNN_DEBUG("Processed multimodal prompt with %zu images (%d successful, %d failed)",
                  result.multimodal_prompt.images.size(), state.successful_loads, state.failed_loads);
    } else if (state.failed_loads > 0) {
        MNN_DEBUG("All multimodal content failed to load, falling back to text-only mode");
    }

    return result;
}

bool PromptProcessor::HandleImageTags(const std::string& prompt_text,
                                          PromptProcessingResult& result,
                                          ProcessorState& state) const {
    bool has_images = false;

    std::regex img_regex("<img>([^<]*)</img>");
    std::smatch match;
    auto search_start = prompt_text.cbegin();

    while (std::regex_search(search_start, prompt_text.cend(), match, img_regex)) {
        if (state.image_index >= config_.max_debug_images) {
            MNN_DEBUG("Reached MAX_DEBUG_IMAGES limit (%d), skipping remaining images", config_.max_debug_images);
            break;
        }

        std::string image_path = match[1].str();
        if (!image_path.empty()) {
            MNN_DEBUG("Found image tag with path: %s", image_path.c_str());

            auto image_var = LoadImageFromPath(image_path);
            if (image_var.get() != nullptr) {
                // The engine looks images up by the text inside <img>...</img>, so the path
                // must be the key. Using "image_N" here silently made every lookup fail and
                // forced a second decode of the file inside the engine.
                MNN::Transformer::PromptImagePart image_part;
                image_part.image_data = image_var;
                auto info = image_var->getInfo();
                if (info != nullptr && info->dim.size() >= 2) {
                    image_part.height = info->dim[0];
                    image_part.width = info->dim[1];
                } else {
                    image_part.height = 0;
                    image_part.width = 0;
                }
                result.multimodal_prompt.images[image_path] = image_part;
                has_images = true;
                state.image_index++;
                state.successful_loads++;
                MNN_DEBUG("Successfully loaded image: %s (%dx%d)",
                          image_path.c_str(), image_part.width, image_part.height);
            } else {
                state.failed_loads++;
                MNN_ERROR("Failed to load image from path: %s", image_path.c_str());
                result.error_message += "Failed to load image: " + image_path + "; ";
                const std::string escaped_path = EscapeForRegex(image_path);
                state.final_prompt = std::regex_replace(
                    state.final_prompt,
                    std::regex("<img>" + escaped_path + "</img>"),
                    ""
                );
            }
        }
        search_start = match.suffix().first;
    }

    return has_images;
}

bool PromptProcessor::HandleVideoTags(const std::string& prompt_text,
                                          PromptProcessingResult& result,
                                          ProcessorState& state) const {
    bool has_videos = false;

    std::regex video_regex("<video>([^<]*)</video>");
    std::smatch match;
    auto search_start = prompt_text.cbegin();

    while (std::regex_search(search_start, prompt_text.cend(), match, video_regex)) {
        if (state.image_index >= config_.max_debug_images) {
            MNN_DEBUG("Reached MAX_DEBUG_IMAGES limit (%d), skipping video processing", config_.max_debug_images);
            break;
        }

        std::string video_path = match[1].str();
        if (!video_path.empty()) {
            MNN_DEBUG("Found video tag with path: %s", video_path.c_str());

            int remaining_slots = config_.max_debug_images - state.image_index;
            if (remaining_slots <= 0) {
                break;
            }

            VideoProcessorConfig video_config = config_.video_processor_config;
            video_config.max_frames = std::min(video_config.max_frames, remaining_slots);
            video_config.max_debug_images = std::min(video_config.max_debug_images, remaining_slots);

            auto video_result = VideoProcessor::ProcessVideoFrames(video_path, video_config);
            if (video_result.success && !video_result.images.empty()) {
                // Use the processed prompt template directly
                const std::string escaped_path = EscapeForRegex(video_path);
                state.final_prompt = std::regex_replace(
                    state.final_prompt,
                    std::regex("<video>" + escaped_path + "</video>"),
                    video_result.prompt_template
                );
                
                // Add images to multimodal_prompt
                for (const auto& pair : video_result.images) {
                    result.multimodal_prompt.images[pair.first] = pair.second;
                    state.image_index++;
                }
                
                has_videos = true;
                state.successful_loads++;
                MNN_DEBUG("Successfully processed video: %s into %zu frames with SmolVLM format",
                          video_path.c_str(), video_result.images.size());
            } else {
                state.failed_loads++;
                MNN_ERROR("Failed to process video from path: %s", video_path.c_str());
                result.error_message += "Failed to process video: " + video_path + "; ";
                const std::string escaped_path = EscapeForRegex(video_path);
                state.final_prompt = std::regex_replace(
                    state.final_prompt,
                    std::regex("<video>" + escaped_path + "</video>"),
                    ""
                );
            }
        }
        search_start = match.suffix().first;
    }

    return has_videos;
}

} // namespace mls
