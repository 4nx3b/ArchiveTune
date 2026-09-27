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

# 16KB page-size support (ported from BitChord's own fix, 77cd233): Android
# 15+ 16KB-page kernels refuse to map LOAD segments aligned only to 4KB — on
# those devices an unaligned libarchivetune_analysis.so either fails to load
# (analysis silently unavailable) or takes the process down at dlopen time.
# -z max-page-size=16384 is a superset of 4KB alignment, so 4KB devices are
# unaffected; APK-side alignment of the uncompressed .so is handled by AGP.
target_link_options(archivetune_analysis PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# archivetune_dsp: the 32-bit float DSP chain + the AAudio exclusive output
# (USB-direct float playback). A separate shared library from the analyzer so
# loading analysis (optional) and loading the DSP playback path stay
# independent; the same conventions apply (16KB pages, -fexceptions for the
# JNI bad_alloc guards, no -ffast-math).
# ---------------------------------------------------------------------------
add_library(archivetune_dsp SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/dsp/float_dsp.cpp
    ${ARCHIVETUNE_NATIVE_DIR}/dsp/aaudio_output.cpp
    jni/dsp_jni.cpp
)

target_include_directories(archivetune_dsp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)

target_compile_options(archivetune_dsp PRIVATE -O3 -fvisibility=hidden -fexceptions)

find_library(log-lib-dsp log)
target_link_libraries(archivetune_dsp ${log-lib-dsp} aaudio)

target_link_options(archivetune_dsp PRIVATE "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# archivetune_crash: the always-loaded crash log capture. A tiny standalone
# library (no dependencies beyond libc/log) so it can be loaded first thing
# at app start, before any playback or analysis library. It installs
# async-signal-safe handlers for the fatal signals and writes a minimal
# report (registers + /proc/self/maps) that CrashReporter.kt enriches with
# the GlobalLog breadcrumbs of the dying session and copies to user-visible
# storage on the next start.
# ---------------------------------------------------------------------------
add_library(archivetune_crash SHARED
    ${ARCHIVETUNE_NATIVE_DIR}/crash/crash_handler.cpp
)

target_include_directories(archivetune_crash PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ARCHIVETUNE_NATIVE_DIR}
)

target_compile_options(archivetune_crash PRIVATE -O2 -fvisibility=hidden -fexceptions)

find_library(log-lib-crash log)
target_link_libraries(archivetune_crash ${log-lib-crash})

target_link_options(archivetune_crash PRIVATE "-Wl,-z,max-page-size=16384")
