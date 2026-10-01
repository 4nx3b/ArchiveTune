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

// JNI bridge to the mel front end.
//
// Kept as thin as a bridge can be: it copies the sample array in, calls
// ComputeBeatSpectrogram, and hands back a flat float[]. No policy, no
// buffering, no threading -- all of that belongs on the Kotlin side where it
// can be cancelled and tested.

#include <jni.h>

#include <limits>
#include <new>
#include <vector>

#include "analyzer/mel_spectrogram.h"
#include "analyzer/resampler.h"

namespace {

// See analysis_jni.cpp: no exception may unwind out of a JNI frame — a
// std::bad_alloc escaping aborts the process with SIGABRT and no Java crash
// log. These helpers keep every allocation failure inside "return empty",
// which the Kotlin callers already read as "no model evidence".
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

jfloatArray AsArray(JNIEnv* env, const std::vector<float>& values) {
  // jsize is 32-bit; an output past 2^31-1 elements would make the cast go
  // negative and hand NewFloatArray a negative length.
  if (values.size() > static_cast<size_t>(std::numeric_limits<jint>::max())) {
    return env->NewFloatArray(0);
  }
  const jsize produced = static_cast<jsize>(values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) {
    return nullptr;  // OOM; the exception is already pending.
  }
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, values.data());
  }
  return result;
}

jfloatArray EmptyFloatArray(JNIEnv* env) {
  return env->NewFloatArray(0);
}

}  // namespace

extern "C" {

// Converts mono float PCM to the model's rate. Separate from nativeCompute
// because the caller decodes at whatever rate the container carries and only
// then knows what conversion is needed.
JNIEXPORT jfloatArray JNICALL
Java_moe_rukamori_archivetune_playback_smart_MelSpectrogram_nativeResample(
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
    return AsArray(env, resampled);
  } catch (const std::bad_alloc&) {
    return EmptyFloatArray(env);
  } catch (...) {
    return EmptyFloatArray(env);
  }
}

// Returns the flattened [frames][kBeatSpectrogramMels] spectrogram, or an
// empty array when the front end declined the input (wrong rate, or shorter
// than one padded frame). The caller derives the frame count by dividing, so
// no second return value is needed.
JNIEXPORT jfloatArray JNICALL
Java_moe_rukamori_archivetune_playback_smart_MelSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray samples,
    jdouble sample_rate) {
  std::vector<float> input;
  if (!CopyIn(env, samples, input)) {
    return EmptyFloatArray(env);
  }

  try {
    const bitchord::smart::BeatSpectrogram spectrogram =
        bitchord::smart::ComputeBeatSpectrogram(input, sample_rate);
    return AsArray(env, spectrogram.values);
  } catch (const std::bad_alloc&) {
    return EmptyFloatArray(env);
  } catch (...) {
    return EmptyFloatArray(env);
  }
}

// The mel band count is part of the model contract rather than a choice, so
// it is read from the header instead of being duplicated in Kotlin.
JNIEXPORT jint JNICALL
Java_moe_rukamori_archivetune_playback_smart_MelSpectrogram_nativeMelCount(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kBeatSpectrogramMels);
}

JNIEXPORT jdouble JNICALL
Java_moe_rukamori_archivetune_playback_smart_MelSpectrogram_nativeSampleRate(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return bitchord::smart::kBeatSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_moe_rukamori_archivetune_playback_smart_MelSpectrogram_nativeHop(
    JNIEnv* /* env */,
    jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kBeatSpectrogramHop);
}

}  // extern "C"
