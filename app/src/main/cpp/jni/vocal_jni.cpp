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

// JNI bridge to the vocal-separation front end.
//
// Unlike the mel front end this one is stereo and linear-frequency, because
// that is what open-unmix was trained on. The layout it produces is bin-major
// rather than frame-major specifically so it matches the model's tensor shape
// [1, 2, bins, frames] with no transpose on the Kotlin side.

#include <jni.h>

#include <limits>
#include <new>
#include <vector>

#include "analyzer/vocal_spectrogram.h"

namespace {

// See analysis_jni.cpp: no exception may unwind out of a JNI frame —
// std::bad_alloc escaping aborts the process with SIGABRT and no Java log.
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

// Takes the two channels as separate arrays rather than one interleaved one,
// mirroring the planar layout the front end wants and avoiding a deinterleave
// on either side of the boundary.
JNIEXPORT jfloatArray JNICALL
Java_moe_rukamori_archivetune_playback_smart_VocalSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
    jfloatArray right,
    jdouble sample_rate) {
  std::vector<std::vector<float>> channels(2);
  if (!CopyIn(env, left, channels[0]) || !CopyIn(env, right, channels[1])) {
    return EmptyFloatArray(env);
  }

  try {
    const bitchord::smart::VocalSpectrogram spectrogram =
        bitchord::smart::ComputeVocalSpectrogram(channels, sample_rate);
    return AsArray(env, spectrogram.values);
  } catch (const std::bad_alloc&) {
    return EmptyFloatArray(env);
  } catch (...) {
    return EmptyFloatArray(env);
  }
}

JNIEXPORT jint JNICALL
Java_moe_rukamori_archivetune_playback_smart_VocalSpectrogram_nativeBins(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kVocalSpectrogramBins);
}

JNIEXPORT jdouble JNICALL
Java_moe_rukamori_archivetune_playback_smart_VocalSpectrogram_nativeSampleRate(
    JNIEnv* /* env */, jclass /* clazz */) {
  return bitchord::smart::kVocalSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_moe_rukamori_archivetune_playback_smart_VocalSpectrogram_nativeHop(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kVocalSpectrogramHop);
}

JNIEXPORT jint JNICALL
Java_moe_rukamori_archivetune_playback_smart_VocalSpectrogram_nativeFftSize(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kVocalSpectrogramFft);
}

}  // extern "C"
