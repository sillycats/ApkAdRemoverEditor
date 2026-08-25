#!/bin/bash
#===============================================================================
# APK 编译环境一键保存脚本（生成 APK_env.conf）
# 用途：将当前已可用的 APK 编译环境参数保存为配置文件，
#       供 restore_env.sh 在需要时一键恢复同样的环境。
# 使用：bash save_env.sh [--sdk /path/to/android-sdk]
#
# 自动探测顺序（未指定时可被覆盖）：
#   Android SDK: --sdk > ANDROID_SDK_ROOT > ANDROID_HOME > local.properties > /opt/android-sdk
#   NDK/CMake/build-tools/platform: 取 SDK 目录内已安装的最新可用版本
#   Gradle:      从 gradle/wrapper/gradle-wrapper.properties 读取
#===============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/APK_env.conf"

# 默认参数（与 build_env.sh / restore_env.sh 内置默认保持一致）
DEFAULT_GRADLE_VERSION="8.5"
DEFAULT_NDK_VERSION="21.4.7075529"
DEFAULT_CMAKE_VERSION="3.22.1"
DEFAULT_BUILD_TOOLS_VERSION="33.0.1"
DEFAULT_PLATFORM_VERSION="android-34"
DEFAULT_SDK_DIR="/opt/android-sdk"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[信息]${NC} $1" >&2; }
log_ok()    { echo -e "${GREEN}[完成]${NC} $1" >&2; }
log_warn()  { echo -e "${YELLOW}[警告]${NC} $1" >&2; }
log_err()   { echo -e "${RED}[错误]${NC} $1" >&2; }

# ──────────────────────────────────────────────
# 参数解析
# ──────────────────────────────────────────────
SDK_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --sdk) SDK_ARG="$2"; shift 2 ;;
        -h|--help)
            echo "用法: bash save_env.sh [--sdk /path/to/android-sdk]"
            echo ""
            echo "参数说明:"
            echo "  --sdk  手动指定 Android SDK 目录（否则自动探测）"
            exit 0 ;;
        *) log_err "未知参数: $1"; exit 1 ;;
    esac
done

# ──────────────────────────────────────────────
# 探测 Android SDK 目录
# ──────────────────────────────────────────────
PROJECT_DIR="$SCRIPT_DIR"
ANDROID_SDK=""

if [ -n "$SDK_ARG" ]; then
    ANDROID_SDK="$SDK_ARG"
    log_info "使用 --sdk 指定: $ANDROID_SDK"
elif [ -n "$ANDROID_SDK_ROOT" ]; then
    ANDROID_SDK="$ANDROID_SDK_ROOT"
    log_info "使用 ANDROID_SDK_ROOT: $ANDROID_SDK"
elif [ -n "$ANDROID_HOME" ]; then
    ANDROID_SDK="$ANDROID_HOME"
    log_info "使用 ANDROID_HOME: $ANDROID_SDK"
elif [ -f "$PROJECT_DIR/local.properties" ] && grep -q "^sdk.dir=" "$PROJECT_DIR/local.properties" 2>/dev/null; then
    ANDROID_SDK="$(grep "^sdk.dir=" "$PROJECT_DIR/local.properties" | head -1 | cut -d= -f2)"
    log_info "从 local.properties 读取: $ANDROID_SDK"
else
    ANDROID_SDK="$DEFAULT_SDK_DIR"
    log_warn "未探测到 SDK 路径，使用默认: $ANDROID_SDK"
fi

# ──────────────────────────────────────────────
# 探测 Gradle 版本（从 wrapper 配置）
# ──────────────────────────────────────────────
GRADLE_VERSION="$DEFAULT_GRADLE_VERSION"
WRAPPER_PROP="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$WRAPPER_PROP" ]; then
    DETECTED_GRADLE=$(grep -oE 'gradle-[0-9.]+-bin\.zip' "$WRAPPER_PROP" 2>/dev/null | head -1 | sed -E 's/gradle-([0-9.]+)-bin\.zip/\1/')
    if [ -n "$DETECTED_GRADLE" ]; then
        GRADLE_VERSION="$DETECTED_GRADLE"
        log_info "从 gradle-wrapper.properties 读取 Gradle $GRADLE_VERSION"
    fi
fi

# ──────────────────────────────────────────────
# 探测 SDK 内已安装的最新组件版本
# ──────────────────────────────────────────────
# 取 SDK 目录内已安装版本（多版本时取数值最大/最新者）
get_latest_dir() {
    local dir="$1"
    if [ -d "$dir" ]; then
        ls -1 "$dir" 2>/dev/null | grep -E '^[0-9]' | sort -V | tail -1
    fi
}

NDK_VERSION="${DEFAULT_NDK_VERSION}"
CMAKE_VERSION="${DEFAULT_CMAKE_VERSION}"
BUILD_TOOLS_VERSION="${DEFAULT_BUILD_TOOLS_VERSION}"
PLATFORM_VERSION="${DEFAULT_PLATFORM_VERSION}"

if [ -n "$ANDROID_SDK" ] && [ -d "$ANDROID_SDK" ]; then
    DETECTED_NDK=$(get_latest_dir "$ANDROID_SDK/ndk")
    [ -z "$DETECTED_NDK" ] && DETECTED_NDK=$(get_latest_dir "$ANDROID_SDK/ndk-stable")
    [ -n "$DETECTED_NDK" ] && NDK_VERSION="$DETECTED_NDK" && log_info "NDK 检测到: $NDK_VERSION"

    DETECTED_CMAKE=$(get_latest_dir "$ANDROID_SDK/cmake")
    [ -n "$DETECTED_CMAKE" ] && CMAKE_VERSION="$DETECTED_CMAKE" && log_info "CMake 检测到: $CMAKE_VERSION"

    DETECTED_BT=$(get_latest_dir "$ANDROID_SDK/build-tools")
    [ -n "$DETECTED_BT" ] && BUILD_TOOLS_VERSION="$DETECTED_BT" && log_info "build-tools 检测到: $BUILD_TOOLS_VERSION"

    DETECTED_PLATFORM=$(get_latest_dir "$ANDROID_SDK/platforms" | sed 's/^android-//')
    if [ -n "$DETECTED_PLATFORM" ]; then
        PLATFORM_VERSION="android-${DETECTED_PLATFORM}"
        log_info "platform 检测到: $PLATFORM_VERSION"
    fi
else
    log_warn "SDK 目录不存在，使用默认组件版本"
fi

# ──────────────────────────────────────────────
# 写入 APK_env.conf
# ──────────────────────────────────────────────
cat > "$ENV_FILE" << EOF
#===========================================================
# APK 编译环境配置文件 (APK_env.conf)
#
# 本文件由 save_env.sh 在 $(date '+%Y-%m-%d %H:%M:%S') 自动生成，
# 供 restore_env.sh 读取后一键恢复环境。
#===========================================================

# 项目根目录（build.gradle 所在目录）
PROJECT_DIR=$PROJECT_DIR

# Android SDK 安装目录
ANDROID_SDK=$ANDROID_SDK

# 所需组件版本
GRADLE_VERSION=$GRADLE_VERSION
NDK_VERSION=$NDK_VERSION
CMAKE_VERSION=$CMAKE_VERSION
BUILD_TOOLS_VERSION=$BUILD_TOOLS_VERSION
PLATFORM_VERSION=$PLATFORM_VERSION
EOF

chmod +x "$0" 2>/dev/null || true

echo ""
log_ok "环境配置已保存到: $ENV_FILE"
echo "  项目目录:      $PROJECT_DIR"
echo "  Android SDK:   $ANDROID_SDK"
echo "  Gradle:        $GRADLE_VERSION"
echo "  NDK:           $NDK_VERSION"
echo "  CMake:         $CMAKE_VERSION"
echo "  build-tools:   $BUILD_TOOLS_VERSION"
echo "  platform:      $PLATFORM_VERSION"
echo ""
echo "在目标环境执行 bash restore_env.sh 即可恢复以上环境。"