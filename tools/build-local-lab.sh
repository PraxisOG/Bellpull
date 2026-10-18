#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd "$(dirname "$0")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
# The Vulkan shader generator is a host build and needs the SDK's Ninja on PATH.
export PATH="$sdk_dir/cmake/3.31.6/bin:$PATH"
export ANDROID_HOME="$sdk_dir"
exec "$project_dir/android/gradlew" -p "$project_dir/android" "$@"
