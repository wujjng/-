#!/usr/bin/env bash
# 一键准备依赖：sherpa-onnx armeabi-v7a 预编译库 + 同版本 Kotlin API 源码 + 中文 KWS 模型
# 用法：bash scripts/fetch_deps.sh
#   可选环境变量：
#     SHERPA_VERSION=2.0.0      sherpa-onnx 版本（tag 为 v$SHERPA_VERSION）
#     KWS_MODEL=...             KWS 模型名
#     GH_MIRROR=https://github.com  国内网络慢时可替换为 GitHub 镜像前缀
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="${SHERPA_VERSION:-2.0.0}"
KWS_MODEL="${KWS_MODEL:-sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01}"
GH="${GH_MIRROR:-https://github.com}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

JNI_DIR=app/src/main/jniLibs/armeabi-v7a
API_DIR=app/src/main/java/com/k2fsa/sherpa/onnx
ASSET_DIR=app/src/main/assets/kws

echo "==> [1/3] 下载 sherpa-onnx v${SHERPA_VERSION} Android 预编译库"
curl -fL --retry 3 -o "$TMP/android.tar.bz2" \
  "$GH/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
mkdir -p "$TMP/android"
tar xjf "$TMP/android.tar.bz2" -C "$TMP/android"
rm -rf "$JNI_DIR" && mkdir -p "$JNI_DIR"
find "$TMP/android" -path '*armeabi-v7a*' -name '*.so' -exec cp {} "$JNI_DIR/" \;
ls -lh "$JNI_DIR"
if [ ! -f "$JNI_DIR/libsherpa-onnx-jni.so" ]; then
  echo "!! 没找到 armeabi-v7a/libsherpa-onnx-jni.so，请检查 SHERPA_VERSION 是否正确"; exit 1
fi

echo "==> [2/3] 拉取同版本 Kotlin API 源码（必须和 .so 同版本，否则 JNI 签名对不上）"
git clone --depth 1 --branch "v${SHERPA_VERSION}" "$GH/k2-fsa/sherpa-onnx.git" "$TMP/src"
rm -rf "$API_DIR" && mkdir -p "$API_DIR"
cp "$TMP/src/sherpa-onnx/kotlin-api/"*.kt "$API_DIR/"
echo "    已复制 $(ls "$API_DIR" | wc -l) 个 Kotlin 文件"

echo "==> [3/3] 下载 KWS 模型 ${KWS_MODEL}"
curl -fL --retry 3 -o "$TMP/kws.tar.bz2" \
  "$GH/k2-fsa/sherpa-onnx/releases/download/kws-models/${KWS_MODEL}.tar.bz2"
tar xjf "$TMP/kws.tar.bz2" -C "$TMP"
rm -rf "$ASSET_DIR" && mkdir -p "$ASSET_DIR"
cp "$TMP/$KWS_MODEL/"*.onnx "$TMP/$KWS_MODEL/tokens.txt" "$ASSET_DIR/"
if [ -f "$TMP/$KWS_MODEL/keywords.txt" ]; then cp "$TMP/$KWS_MODEL/keywords.txt" "$ASSET_DIR/keywords_example.txt"; fi
ls -lh "$ASSET_DIR"

echo "==> 依赖准备完成。接下来：gradle assembleDebug（或用 Android Studio 打开工程直接 Run）"
