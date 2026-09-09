#!/usr/bin/env bash
# Build TDLight (https://github.com/tdlight-team/tdlight) native libraries
# for Android together with the generated org.drinkless.tdlib Java binding
# (Client.java + TdApi.java), so the app can drive TDLight through td-ktx.
#
# Mirrors tdlight's example/android pipeline (build-openssl.sh +
# build-tdlib.sh) with two differences:
#   * each ABI build directory is deleted right after its .so is collected,
#     keeping peak disk usage viable on a CI runner;
#   * javadoc generation is skipped (the app only needs the sources).
#
# Output layout ($OUT_DIR):
#   java/org/drinkless/tdlib/{Client.java,TdApi.java}
#   libtdjni-<abi>.so          (stripped, static OpenSSL + static libc++)
#   libtdjni-<abi>.so.gz       (gzip -9 asset served from the GitHub release)
#   digests.txt                (SHA-256 of every produced file)
#
# Usage: build-tdlight-android.sh <tdlight-sha> <android-sdk-root> <out-dir> [abis...]
set -euo pipefail

TDLIGHT_SHA=${1:?tdlight commit sha}
ANDROID_SDK_ROOT=$(cd "${2:?android sdk root}" && pwd)
OUT_DIR=$(mkdir -p "${3:?out dir}" && cd "${3}" && pwd)
shift 3 || true
ABIS=("$@")
if [ "${#ABIS[@]}" -eq 0 ]; then
    ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
fi

NDK_VERSION=23.2.8568313
ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
OPENSSL_VERSION=OpenSSL_1_1_1w
WORK=$(pwd)/tdlight-work
rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"

git clone --filter=blob:none https://github.com/tdlight-team/tdlight tdlight
cd tdlight
git fetch --depth 1 origin "$TDLIGHT_SHA"
git checkout --detach FETCH_HEAD
git rev-parse HEAD
cd ..

PATH="$ANDROID_SDK_ROOT/cmake/3.22.1/bin:$PATH"
export PATH

cd tdlight/example/android

# ---------------------------------------------------------------------------
# OpenSSL (static) for every ABI — same invocation as tdlight's
# example/android/build-openssl.sh, empty 5th argument = no-shared.
# ---------------------------------------------------------------------------
./build-openssl.sh "$ANDROID_SDK_ROOT" "$NDK_VERSION" "$WORK/third-party/openssl" "$OPENSSL_VERSION" ""
OPENSSL_INSTALL_DIR="$WORK/third-party/openssl"

# ---------------------------------------------------------------------------
# Host build: TDLib source generation + the Java API generator
# (tl_generate_java), then AddIntDef.php post-processing — identical to
# build-tdlib.sh's Java interface path.
# ---------------------------------------------------------------------------
mkdir -p build-native
cd build-native
cmake -DTD_GENERATE_SOURCE_FILES=ON ..
cmake --build . -j"$(nproc)"
cd ..

cmake --build build-native --target tl_generate_java
php AddIntDef.php org/drinkless/tdlib/TdApi.java

mkdir -p "$OUT_DIR/java/org/drinkless/tdlib"
cp -p ../java/org/drinkless/tdlib/Client.java "$OUT_DIR/java/org/drinkless/tdlib/Client.java"
cp -p org/drinkless/tdlib/TdApi.java "$OUT_DIR/java/org/drinkless/tdlib/TdApi.java"
rm -rf org
rm -rf build-native

# ---------------------------------------------------------------------------
# Cross builds: one stripped libtdjni.so per ABI (static libc++_static).
# The example/android CMakeLists adds -flto=thin -Oz for cross builds and
# strips the binary in a POST_BUILD step, so no extra handling is needed.
# ---------------------------------------------------------------------------
STRIP="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for ABI in "${ABIS[@]}"; do
    mkdir -p "build-$ABI"
    cd "build-$ABI"
    cmake \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DOPENSSL_ROOT_DIR="$OPENSSL_INSTALL_DIR/$ABI" \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -GNinja \
        -DANDROID_ABI="$ABI" \
        -DANDROID_STL=c++_static \
        -DANDROID_PLATFORM=android-16 \
        ..
    cmake --build . --target tdjni
    cp -p libtdjni.so "$OUT_DIR/libtdjni-$ABI.so"
    "$STRIP" --strip-debug --strip-unneeded "$OUT_DIR/libtdjni-$ABI.so"
    cd ..
    rm -rf "build-$ABI"
done

# ---------------------------------------------------------------------------
# Digests + gzip assets
# ---------------------------------------------------------------------------
cd "$OUT_DIR"
for abi in "${ABIS[@]}"; do
    gzip -9 -c "libtdjni-$abi.so" > "libtdjni-$abi.so.gz"
done
{
    sha256sum libtdjni-*.so java/org/drinkless/tdlib/*.java
    du -h libtdjni-*.so libtdjni-*.so.gz
} | tee digests.txt

echo "TDLight Android build complete:"
ls -la "$OUT_DIR"
