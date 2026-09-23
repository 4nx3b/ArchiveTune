# Native analysis front end for Automix (ported from BitChord, which itself
# derives the analyzer from Orchard — see the headers in ../../../native).
#
# Phase 1: whole-track DSP analysis (tempo/key/energy/structure via
# autocorrelation, no ML model). Phase 2: the log-mel front end the Beat
# This! ONNX beat/downbeat model reads (mel_spectrogram.cpp / mel_jni.cpp).
# Phase 3: the linear-frequency STFT the open-unmix vocal-separation model
# reads (vocal_spectrogram.cpp / vocal_jni.cpp). Both models themselves run
# through ONNX Runtime on the Kotlin side, not here. The shared analyzer
# sources live under the repo's root `native/` tree.

cmake_minimum_required(VERSION 3.22.1)
project(archivetune_analysis LANGUAGES CXX)

get_filename_component(ARCHIVETUNE_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(archivetune_analysis SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/resampler.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(archivetune_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)

# -ffast-math is deliberately NOT enabled: the analyzer's log/mel-adjacent
# arithmetic (see audio_analysis.cpp) is tuned against exact IEEE behaviour.
# -fexceptions is explicit because the JNI bridges catch std::bad_alloc so an
# allocation failure degrades to "no analysis" instead of aborting the process
# (an exception unwinding out of a JNI frame is a hard SIGABRT with no log).
target_compile_options(archivetune_analysis PRIVATE -O3 -fvisibility=hidden -fexceptions)

find_library(log-lib log)
target_link_libraries(archivetune_analysis ${log-lib})
