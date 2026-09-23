/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// JNI bridge to the whole-track DSP analyzer and its resampler.
//
// AnalysisResult carries about twenty fields including strings, which is more
// than is worth marshalling field by field through JNI. It is serialized to
// JSON instead: analysis runs once per track, so the cost is irrelevant
// beside the decode around it, and a string is far easier to log and to test
// against than a hand-packed buffer.
//
// Only the subset the transition policy actually reads is emitted. Chroma,
// the mid and high energy curves, loudness, peak and dynamic range are
// computed by the analyzer but nothing downstream consumes them, and emitting
// them would mean three more float arrays per track for no reader.

#include <jni.h>

#include <cstdio>
#include <limits>
#include <new>
#include <string>
#include <vector>

#include "analyzer/audio_analysis.h"
#include "analyzer/resampler.h"

namespace {

// A helper that turns "copy the Java array into a native vector" into a
// nullable result so allocation failure (std::bad_alloc) can degrade to "no
// analysis this tick" instead of unwinding out of the JNI frame — an
// exception escaping a native method aborts the process with SIGABRT and no
// Java crash log.
bool CopyIn(JNIEnv* env, jfloatArray source, std::vector<float>& out) {
  const jsize count = env->GetArrayLength(source);
  if (count <= 0) return true;
  if (static_cast<size_t>(count) >
      static_cast<size_t>(std::numeric_limits<jint>::max())) {
    return false;
  }
  try {
    out.resize(static_cast<size_t>(count));
    env->GetFloatArrayRegion(source, 0, count, out.data());
    return true;
  } catch (const std::bad_alloc&) {
    return false;
  } catch (...) {
    return false;
  }
}

// The JNI bridge's own allocations can also throw; everything below funnels
// through these so no exception ever crosses the JNI boundary.
jfloatArray EmptyFloatArray(JNIEnv* env) {
  return env->NewFloatArray(0);
}


// The analyzer's strings are its own literals -- key names like "C# minor"
// and candidate types like "main_drop". Key names are emitted in UTF-8 with
// real accidentals (C\xE2\x99\xAF, E\xE2\x99\xAD), so every byte >= 0x80 is
// passed through verbatim: org.json on the Kotlin side decodes UTF-8, and the
// transition policy matches those exact spellings. Dropping them (the original
// "known ASCII" filter) silently flattened every accidental key to its
// natural neighbor and pitched the harmonic routing off by a semitone.
// Control characters and the JSON delimiters are the only escapes needed.
void AppendString(std::string& out, const std::string& value) {
  out += '"';
  for (const char character : value) {
    const unsigned char byte = static_cast<unsigned char>(character);
    if ((byte >= 0x20 && byte < 0x7F && byte != '"' && byte != '\\') || byte >= 0x80) {
      out += character;
    }
  }
  out += '"';
}

void AppendNumber(std::string& out, double value) {
  // Not finite means the field never got a defensible value; null reads as
  // absent on the Kotlin side, which is what every consumer already handles.
  if (!(value == value) || value > 1e308 || value < -1e308) {
    out += "null";
    return;
  }
  char buffer[32];
  snprintf(buffer, sizeof(buffer), "%.6g", value);
  out += buffer;
}

void AppendDoubles(std::string& out, const std::vector<double>& values) {
  out += '[';
  for (size_t index = 0; index < values.size(); ++index) {
    if (index > 0) out += ',';
    AppendNumber(out, values[index]);
  }
  out += ']';
}

void AppendEnergyCurve(std::string& out, const std::vector<bitchord::smart::EnergyPoint>& points) {
  out += '[';
  for (size_t index = 0; index < points.size(); ++index) {
    if (index > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[index].time);
    out += ",\"e\":";
    AppendNumber(out, points[index].energy);
    out += '}';
  }
  out += ']';
}

void AppendCuePoints(std::string& out, const std::vector<bitchord::smart::MixCuePoint>& points) {
  out += '[';
  for (size_t index = 0; index < points.size(); ++index) {
    if (index > 0) out += ',';
    out += "{\"t\":";
    AppendNumber(out, points[index].time);
    out += ",\"s\":";
    AppendNumber(out, points[index].score);
    out += ",\"y\":";
    AppendString(out, points[index].type);
    out += '}';
  }
  out += ']';
}

void AppendField(std::string& out, const char* name, double value, bool first = false) {
  if (!first) out += ',';
  out += '"';
  out += name;
  out += "\":";
  AppendNumber(out, value);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_moe_rukamori_archivetune_playback_smart_TrackFeatures_nativeAnalyze(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble sample_rate,
    jdouble duration) {
  std::vector<float> input;
  if (!CopyIn(env, samples, input)) {
    return env->NewStringUTF("{}");
  }

  try {
  const bitchord::smart::AnalysisResult result =
      bitchord::smart::AnalyzeAudio(input, sample_rate, duration);

  std::string json;
  // A whole-track energy curve dominates the output; reserving up front keeps
  // this from repeatedly reallocating a string that reaches tens of kilobytes.
  json.reserve(8192 + result.energy_curve.size() * 24);

  json += '{';
  AppendField(json, "duration", result.duration, true);
  AppendField(json, "bpm", result.bpm);
  AppendField(json, "beatInterval", result.beat_interval);
  AppendField(json, "firstBeat", result.first_beat);
  AppendField(json, "beatConfidence", result.beat_confidence);
  AppendField(json, "keyConfidence", result.key_confidence);
  AppendField(json, "audibleStartTime", result.audible_start_time);
  AppendField(json, "pickupTime", result.pickup_time);
  AppendField(json, "introEndTime", result.intro_end_time);
  AppendField(json, "outroStartTime", result.outro_start_time);
  AppendField(json, "contentEndTime", result.content_end_time);
  AppendField(json, "mixInTime", result.mix_in_time);
  AppendField(json, "mixOutTime", result.mix_out_time);
  AppendField(json, "vocalProbability", result.vocal_probability);

  json += ",\"key\":";
  AppendString(json, result.key);
  json += ",\"downbeats\":";
  AppendDoubles(json, result.downbeats);
  json += ",\"phraseBoundaries\":";
  AppendDoubles(json, result.phrase_boundaries);
  json += ",\"vocalActivityMask\":";
  AppendDoubles(json, result.vocal_activity_mask);
  json += ",\"energyCurve\":";
  AppendEnergyCurve(json, result.energy_curve);
  json += ",\"lowEnergyCurve\":";
  AppendEnergyCurve(json, result.low_energy_curve);
  json += ",\"mixInCandidates\":";
  AppendCuePoints(json, result.mix_in_candidates);
  json += ",\"mixOutCandidates\":";
  AppendCuePoints(json, result.mix_out_candidates);
  json += '}';

  return env->NewStringUTF(json.c_str());
  } catch (const std::bad_alloc&) {
    // Degradation, not termination: the Kotlin side parses "{}" as an
    // all-defaults analysis and the policy falls back to a plain fade.
    return env->NewStringUTF("{}");
  } catch (...) {
    return env->NewStringUTF("{}");
  }
}

JNIEXPORT jdouble JNICALL
Java_moe_rukamori_archivetune_playback_smart_TrackFeatures_nativeSampleRate(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  // The rate the analyzer's window and hop constants assume.
  return 11025.0;
}

// Converts mono float PCM to the analyzer's rate. Separate from nativeAnalyze
// because the caller decodes at whatever rate the container carries and only
// then knows what conversion is needed.
JNIEXPORT jfloatArray JNICALL
Java_moe_rukamori_archivetune_playback_smart_TrackFeatures_nativeResample(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble input_rate,
    jdouble output_rate) {
  std::vector<float> input;
  if (!CopyIn(env, samples, input)) {
    return EmptyFloatArray(env);
  }

  try {
  const std::vector<float> resampled =
      bitchord::smart::Resample(input, input_rate, output_rate);

  // jsize is 32-bit: an output past 2^31-1 elements would make the cast go
  // negative and hand NewFloatArray a negative length.
  if (resampled.size() >
      static_cast<size_t>(std::numeric_limits<jint>::max())) {
    return EmptyFloatArray(env);
  }
  const jsize produced = static_cast<jsize>(resampled.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;  // OOM; the exception is already pending.
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, resampled.data());
  }
  return result;
  } catch (const std::bad_alloc&) {
    return EmptyFloatArray(env);
  } catch (...) {
    return EmptyFloatArray(env);
  }
}

}  // extern "C"
