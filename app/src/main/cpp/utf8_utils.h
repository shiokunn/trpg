#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace trpg {

/**
 * Convert a Java string (UTF-16) to standard UTF-8.
 *
 * JNI's GetStringUTFChars uses Modified UTF-8, which cannot represent
 * supplementary characters as standard UTF-8. GetStringChars gives us the
 * actual UTF-16 code units instead.
 */
std::string JniStringToUtf8(JNIEnv * env, jstring value);

/**
 * Convert standard UTF-8 to a Java string (UTF-16).
 *
 * Incomplete or invalid byte sequences are replaced with U+FFFD. Returns
 * nullptr when JNI has a pending exception (for example out of memory).
 */
jstring Utf8ToJniString(JNIEnv * env, const std::string & value);

/**
 * Decode as much complete UTF-8 as possible from `input` into UTF-16 code
 * units. `consumed` receives the number of bytes consumed. When
 * `is_final_flush` is false, an incomplete trailing sequence is left
 * unconsumed. When true, invalid and incomplete bytes become U+FFFD.
 */
void AppendValidUtf8ToUtf16(
    const std::string & input,
    size_t & consumed,
    std::vector<jchar> & output,
    bool is_final_flush = false);

/**
 * Streaming decoder for token pieces. Bytes that end in the middle of a
 * multi-byte UTF-8 sequence are retained until the next piece.
 */
class Utf8StreamDecoder {
public:
    std::vector<jchar> append(const std::string & piece);
    std::vector<jchar> flush();
    void clear();
    const std::string & pending() const { return pending_; }
    bool has_pending() const { return !pending_.empty(); }

private:
    std::string pending_;
};

} // namespace trpg
