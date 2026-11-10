#include "utf8_utils.h"

#include <algorithm>

namespace trpg {
namespace {

void AppendUtf8Codepoint(std::string & out, uint32_t codepoint) {
    if (codepoint <= 0x7F) {
        out.push_back(static_cast<char>(codepoint));
        return;
    }
    if (codepoint <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        return;
    }
    if (codepoint <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        return;
    }
    if (codepoint <= 0x10FFFF) {
        out.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        return;
    }
    out.append("\xEF\xBF\xBD", 3);
}

void AppendReplacement(std::vector<jchar> & output) {
    output.push_back(static_cast<jchar>(0xFFFD));
}

// At final flush an incomplete sequence must collapse into a single U+FFFD.
// Consume the lead byte plus the continuation bytes that belong to it so the
// tail of one broken character is not reported as several replacements.
void ConsumeIncompleteSequence(const std::string & input, size_t & consumed, size_t needed) {
    const size_t available = input.size() - consumed;
    const size_t reachable = std::min(needed, available);
    size_t count = 1;
    while (count < reachable) {
        const auto byte = static_cast<uint8_t>(input[consumed + count]);
        if ((byte & 0xC0) != 0x80) {
            break;
        }
        ++count;
    }
    consumed += count;
}

void AppendCodepointAsUtf16(std::vector<jchar> & output, uint32_t codepoint) {
    if (codepoint <= 0xFFFF) {
        output.push_back(static_cast<jchar>(codepoint));
        return;
    }
    if (codepoint <= 0x10FFFF) {
        codepoint -= 0x10000;
        output.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
        output.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
        return;
    }
    AppendReplacement(output);
}

} // namespace

void AppendValidUtf8ToUtf16(
    const std::string & input,
    size_t & consumed,
    std::vector<jchar> & output,
    bool is_final_flush) {
    consumed = 0;
    const size_t length = input.size();

    while (consumed < length) {
        const auto first = static_cast<uint8_t>(input[consumed]);
        uint32_t codepoint = 0;
        size_t needed = 0;

        if (first <= 0x7F) {
            codepoint = first;
            needed = 1;
        } else if (first >= 0xC2 && first <= 0xDF) {
            needed = 2;
            if (length - consumed < needed) {
                if (!is_final_flush) {
                    break;
                }
                AppendReplacement(output);
                ConsumeIncompleteSequence(input, consumed, needed);
                continue;
            }
            const auto second = static_cast<uint8_t>(input[consumed + 1]);
            if ((second & 0xC0) != 0x80) {
                AppendReplacement(output);
                consumed += 1;
                continue;
            }
            codepoint = (static_cast<uint32_t>(first & 0x1F) << 6) |
                        static_cast<uint32_t>(second & 0x3F);
        } else if (first >= 0xE0 && first <= 0xEF) {
            needed = 3;
            if (length - consumed < needed) {
                if (!is_final_flush) {
                    break;
                }
                AppendReplacement(output);
                ConsumeIncompleteSequence(input, consumed, needed);
                continue;
            }
            const auto second = static_cast<uint8_t>(input[consumed + 1]);
            const auto third = static_cast<uint8_t>(input[consumed + 2]);
            const bool continuation_ok = ((second & 0xC0) == 0x80) && ((third & 0xC0) == 0x80);
            const bool overlong = (first == 0xE0 && second < 0xA0);
            const bool surrogate = (first == 0xED && second >= 0xA0);
            if (!continuation_ok || overlong || surrogate) {
                AppendReplacement(output);
                consumed += 1;
                continue;
            }
            codepoint = (static_cast<uint32_t>(first & 0x0F) << 12) |
                        (static_cast<uint32_t>(second & 0x3F) << 6) |
                        static_cast<uint32_t>(third & 0x3F);
        } else if (first >= 0xF0 && first <= 0xF4) {
            needed = 4;
            if (length - consumed < needed) {
                if (!is_final_flush) {
                    break;
                }
                AppendReplacement(output);
                ConsumeIncompleteSequence(input, consumed, needed);
                continue;
            }
            const auto second = static_cast<uint8_t>(input[consumed + 1]);
            const auto third = static_cast<uint8_t>(input[consumed + 2]);
            const auto fourth = static_cast<uint8_t>(input[consumed + 3]);
            const bool continuation_ok = ((second & 0xC0) == 0x80) &&
                                         ((third & 0xC0) == 0x80) &&
                                         ((fourth & 0xC0) == 0x80);
            const bool overlong = (first == 0xF0 && second < 0x90);
            const bool out_of_range = (first == 0xF4 && second >= 0x90);
            if (!continuation_ok || overlong || out_of_range) {
                AppendReplacement(output);
                consumed += 1;
                continue;
            }
            codepoint = (static_cast<uint32_t>(first & 0x07) << 18) |
                        (static_cast<uint32_t>(second & 0x3F) << 12) |
                        (static_cast<uint32_t>(third & 0x3F) << 6) |
                        static_cast<uint32_t>(fourth & 0x3F);
        } else {
            // Continuation byte without a lead byte, or an invalid lead byte.
            AppendReplacement(output);
            consumed += 1;
            continue;
        }

        AppendCodepointAsUtf16(output, codepoint);
        consumed += needed;
    }
}

std::string JniStringToUtf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const jsize length = env->GetStringLength(value);
    if (length < 0) {
        return {};
    }
    if (length == 0) {
        return {};
    }

    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result;
    result.reserve(static_cast<size_t>(length));

    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = static_cast<uint32_t>(chars[i]);
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF) {
            if (i + 1 < length) {
                const uint32_t low = static_cast<uint32_t>(chars[i + 1]);
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    codepoint = 0x10000 +
                                ((codepoint - 0xD800) << 10) +
                                (low - 0xDC00);
                    ++i;
                } else {
                    codepoint = 0xFFFD;
                }
            } else {
                codepoint = 0xFFFD;
            }
        } else if (codepoint >= 0xDC00 && codepoint <= 0xDFFF) {
            codepoint = 0xFFFD;
        }
        AppendUtf8Codepoint(result, codepoint);
    }

    env->ReleaseStringChars(value, chars);
    return result;
}

jstring Utf8ToJniString(JNIEnv * env, const std::string & value) {
    if (value.empty()) {
        return env->NewString(nullptr, 0);
    }

    size_t consumed = 0;
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    AppendValidUtf8ToUtf16(value, consumed, utf16, true);

    if (utf16.empty()) {
        return env->NewString(nullptr, 0);
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::vector<jchar> Utf8StreamDecoder::append(const std::string & piece) {
    if (piece.empty()) {
        return {};
    }

    std::vector<jchar> output;

    // A pending incomplete sequence can only be completed by continuation bytes.
    // If the new piece does not start with one, the pending sequence is already
    // broken: flush it as exactly one U+FFFD before decoding the new data, so a
    // truncated character never merges with the character that follows it.
    if (!pending_.empty() && (static_cast<uint8_t>(piece[0]) & 0xC0) != 0x80) {
        size_t pending_consumed = 0;
        AppendValidUtf8ToUtf16(pending_, pending_consumed, output, true);
        pending_.clear();
    }

    pending_.append(piece);

    size_t consumed = 0;
    AppendValidUtf8ToUtf16(pending_, consumed, output, false);
    if (consumed > 0) {
        pending_.erase(0, consumed);
    }
    return output;
}

std::vector<jchar> Utf8StreamDecoder::flush() {
    if (pending_.empty()) {
        return {};
    }

    size_t consumed = 0;
    std::vector<jchar> output;
    AppendValidUtf8ToUtf16(pending_, consumed, output, true);
    pending_.clear();
    return output;
}

void Utf8StreamDecoder::clear() {
    pending_.clear();
}

} // namespace trpg
