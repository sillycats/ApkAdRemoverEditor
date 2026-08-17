#!/bin/bash
#===============================================================================
# APK 编译环境一键恢复脚本（独立版，无需 APK_env.conf）
# 用途：在新环境或环境丢失后，一键恢复 APK 编译所需的所有配置
# 使用：bash build_env.sh [--skip-sdk] [--skip-mirrors]
#
# 国内镜像源（按速度排序，自动切换）：
#   Gradle 发行版:  腾讯云 / 华为云 / 官方
#   Android SDK:    Google 官方 / 腾讯云 / 华为云
#   Maven 仓库:     阿里云 / 腾讯云 / 华为云 / 官方
#===============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"

SKIP_SDK=false
SKIP_MIRRORS=false
for arg in "$@"; do
    case $arg in
        --skip-sdk) SKIP_SDK=true ;;
        --skip-mirrors) SKIP_MIRRORS=true ;;
        --help|-h)
            echo "用法: bash build_env.sh [--skip-sdk] [--skip-mirrors]"
            echo ""
            echo "参数说明:"
            echo "  --skip-sdk           跳过 Android SDK 安装"
            echo "  --skip-mirrors       跳过仓库镜像配置"
            exit 0 ;;
    esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[信息]${NC} $1" >&2; }
log_ok()    { echo -e "${GREEN}[完成]${NC} $1" >&2; }
log_warn()  { echo -e "${YELLOW}[警告]${NC} $1" >&2; }
log_err()   { echo -e "${RED}[错误]${NC} $1" >&2; }

echo ""
echo "============================================"
echo "  APK 编译环境一键恢复工具"
echo "  版本: 2.0 | NDK: 21.4.7075529"
echo "  多镜像源自动切换 | 直连加速"
echo "============================================"
echo ""

# ──────────────────────────────────────────────
# 项目编译参数
# ──────────────────────────────────────────────
NDK_VERSION="21.4.7075529"
CMAKE_VERSION="3.22.1"
BUILD_TOOLS_VERSION="33.0.1"
PLATFORM_VERSION="android-34"
GRADLE_VERSION="8.5"
SDK_DIR="/opt/android-sdk"

# ──────────────────────────────────────────────
# 镜像源定义（按速度排序，前者优先）
# ──────────────────────────────────────────────

# Gradle 发行版镜像
GRADLE_MIRRORS=(
    "https://mirrors.cloud.tencent.com/gradle"
    "https://mirrors.huaweicloud.com/gradle"
    "https://services.gradle.org/distributions"
    "https://downloads.gradle-dn.com/distributions"
)

# Android SDK command-line tools 镜像
CMDTOOLS_MIRRORS=(
    "https://dl.google.com/android/repository"
    "https://mirrors.cloud.tencent.com/android/repository"
    "https://mirrors.huaweicloud.com/android/repository"
)
CMDTOOLS_FILE="commandlinetools-linux-11076708_latest.zip"

# Maven 仓库镜像（用于 build.gradle，腾讯云最快，阿里云覆盖最全）
MAVEN_MIRRORS_BUILD=(
    "maven { url 'https://mirrors.cloud.tencent.com/nexus/repository/maven-public/' }"
    "maven { url 'https://maven.aliyun.com/repository/google/' }"
    "maven { url 'https://maven.aliyun.com/repository/public/' }"
    "maven { url 'https://maven.aliyun.com/repository/gradle-plugin/' }"
    "maven { url 'https://maven.aliyun.com/repository/central/' }"
    "maven { url 'https://jitpack.io' }"
)

# Maven 仓库镜像（用于 settings.gradle pluginManagement / dependencyResolutionManagement）
MAVEN_MIRRORS_SETTINGS=(
    "maven { url 'https://mirrors.cloud.tencent.com/nexus/repository/maven-public/' }"
    "maven { url 'https://maven.aliyun.com/repository/gradle-plugin/' }"
    "maven { url 'https://maven.aliyun.com/repository/google/' }"
    "maven { url 'https://maven.aliyun.com/repository/public/' }"
    "maven { url 'https://maven.aliyun.com/repository/central/' }"
    "maven { url 'https://jitpack.io' }"
)

# ──────────────────────────────────────────────
# 通用函数：从多个镜像源下载文件
# ──────────────────────────────────────────────
download_from_mirrors() {
    local output_file="$1"
    local filename="$2"
    shift 2
    local mirrors=("$@")

    for base_url in "${mirrors[@]}"; do
        local full_url="${base_url}/${filename}"
        log_info "  尝试下载: $full_url"
        if curl -fsSL --connect-timeout 30 --max-time 600 -o "$output_file" "$full_url" 2>/dev/null; then
            if [ -s "$output_file" ]; then
                log_ok "  下载成功: $full_url"
                return 0
            fi
        fi
        log_warn "  下载失败，尝试下一个镜像..."
    done
    return 1
}

# 选择最快可用镜像源
select_fastest_mirror() {
    local filename="$1"
    shift 1
    local mirrors=("$@")
    local best_url=""
    local best_time=999

    for base_url in "${mirrors[@]}"; do
        local test_url="${base_url}/${filename}"
        local start_time=$(date +%s%N)
        if curl -fsSL --connect-timeout 10 --max-time 30 --head "$test_url" >/dev/null 2>&1; then
            local end_time=$(date +%s%N)
            local elapsed=$(( (end_time - start_time) / 1000000 ))
            log_info "  镜像 $base_url 响应时间: ${elapsed}ms"
            if [ "$elapsed" -lt "$best_time" ]; then
                best_time=$elapsed
                best_url="$base_url"
            fi
        fi
    done

    echo "$best_url"
}

# ──────────────────────────────────────────────
# Step 1: 检查/安装 JDK
# ──────────────────────────────────────────────
log_info "[1/7] 检查 JDK 环境..."
JAVA_MAJOR=""
if java -version 2>&1 | grep -q "version \"11"; then
    JAVA_MAJOR=11
elif java -version 2>&1 | grep -q "version \"17"; then
    JAVA_MAJOR=17
elif java -version 2>&1 | grep -q "version \"21"; then
    JAVA_MAJOR=21
fi

if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 11 ]; then
    log_ok "  Java 已就绪: $(java -version 2>&1 | head -1)"
else
    log_warn "  Java 未找到或版本过低，尝试安装 OpenJDK 17..."
    if command -v apt-get &>/dev/null; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq 2>/dev/null
        apt-get install -y -qq openjdk-17-jdk-headless 2>/dev/null || \
        apt-get install -y -qq openjdk-17-jdk 2>/dev/null || \
        apt-get install -y -qq openjdk-11-jdk-headless 2>/dev/null || \
        apt-get install -y -qq openjdk-11-jdk 2>/dev/null || {
            log_err "  Java 安装失败，请手动安装 JDK 11 或 17"
            exit 1
        }
    elif command -v yum &>/dev/null; then
        yum install -y java-17-openjdk-devel 2>/dev/null || \
        yum install -y java-11-openjdk-devel 2>/dev/null || {
            log_err "  Java 安装失败，请手动安装 JDK 11 或 17"
            exit 1
        }
    else
        log_err "  无法自动安装 Java，请手动安装 JDK 11 或 17"
        exit 1
    fi
    log_ok "  Java 安装完成"
fi

JAVA_HOME_VAL=$(dirname $(dirname $(readlink -f $(which java) 2>/dev/null || echo "/usr/bin/java")))
export JAVA_HOME="$JAVA_HOME_VAL"
export PATH="$JAVA_HOME/bin:$PATH"
log_info "  JAVA_HOME=$JAVA_HOME"
echo ""

# ──────────────────────────────────────────────
# Step 2: 安装 Android SDK
# ──────────────────────────────────────────────
log_info "[2/7] 检查 Android SDK..."

if $SKIP_SDK; then
    log_info "  (--skip-sdk) 跳过 SDK 安装"
else
    if [ -d "$SDK_DIR/platforms/$PLATFORM_VERSION" ] && [ -d "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION" ]; then
        log_ok "  Android SDK 已就绪: $SDK_DIR"
    else
        log_warn "  Android SDK 不完整，需要安装必要组件..."

        # 检查 cmdline-tools
        SDKMANAGER=""
        for CM_PATH in \
            "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
            "$SDK_DIR/tools/bin/sdkmanager" \
            "$SDK_DIR/cmdline-tools/bin/sdkmanager"; do
            if [ -x "$CM_PATH" ]; then
                SDKMANAGER="$CM_PATH"
                break
            fi
        done

        if [ -z "$SDKMANAGER" ]; then
            log_info "  下载 Android SDK command-line tools..."
            mkdir -p "$SDK_DIR/cmdline-tools"

            CMDTOOLS_FILE_PATH="/tmp/${CMDTOOLS_FILE}"
            if download_from_mirrors "$CMDTOOLS_FILE_PATH" "$CMDTOOLS_FILE" "${CMDTOOLS_MIRRORS[@]}"; then
                unzip -qo "$CMDTOOLS_FILE_PATH" -d "$SDK_DIR/cmdline-tools" 2>/dev/null
                if [ -d "$SDK_DIR/cmdline-tools/cmdline-tools" ] && [ ! -d "$SDK_DIR/cmdline-tools/latest" ]; then
                    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
                fi
                rm -f "$CMDTOOLS_FILE_PATH"
                SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
                log_ok "  SDK command-line tools 安装完成"
            else
                log_err "  所有镜像源均下载失败，请手动下载: $CMDTOOLS_FILE"
                exit 1
            fi
        fi

        log_info "  使用 sdkmanager 安装 SDK 组件 (这需要几分钟)..."
        mkdir -p "$SDK_DIR"

        # 接受 license
        yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true

        # 安装必要组件
        "$SDKMANAGER" --sdk_root="$SDK_DIR" \
            "platforms;$PLATFORM_VERSION" \
            "build-tools;$BUILD_TOOLS_VERSION" \
            "platform-tools" \
            2>&1 | while IFS= read -r line; do
                if echo "$line" | grep -qi "installed\|complete\|success"; then
                    log_info "  $line"
                fi
            done

        log_ok "  SDK 组件安装完成"
    fi
fi
echo ""

# ──────────────────────────────────────────────
# Step 3: 生成 local.properties
# ──────────────────────────────────────────────
log_info "[3/7] 生成 local.properties..."
cat > "$PROJECT_DIR/local.properties" << EOF
sdk.dir=$SDK_DIR
EOF
log_ok "  local.properties 已生成"
echo ""

# ──────────────────────────────────────────────
# Step 4a: 配置 Gradle 代理（检测环境变量）
# ──────────────────────────────────────────────
log_info "[4a/7] 配置 Gradle 代理..."
GRADLE_PROPERTIES="$PROJECT_DIR/gradle.properties"
if [ -f "$GRADLE_PROPERTIES" ]; then
    if grep -q "systemProp.http.proxyHost" "$GRADLE_PROPERTIES" 2>/dev/null; then
        log_ok "  gradle.properties 代理已配置，跳过"
    else
        # 检测环境变量中的代理
        PROXY_HOST=""
        PROXY_PORT=""
        if [ -n "$https_proxy" ]; then
            PROXY_URL=$(echo "$https_proxy" | sed 's|http://||')
            PROXY_HOST=$(echo "$PROXY_URL" | cut -d: -f1)
            PROXY_PORT=$(echo "$PROXY_URL" | cut -d: -f2)
        elif [ -n "$http_proxy" ]; then
            PROXY_URL=$(echo "$http_proxy" | sed 's|http://||')
            PROXY_HOST=$(echo "$PROXY_URL" | cut -d: -f1)
            PROXY_PORT=$(echo "$PROXY_URL" | cut -d: -f2)
        fi
        if [ -n "$PROXY_HOST" ] && [ -n "$PROXY_PORT" ]; then
            cat >> "$GRADLE_PROPERTIES" << EOF

# === 代理配置（由 build_env.sh 自动检测添加）===
# 所有流量通过本地代理，仅排除本地地址
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
systemProp.http.nonProxyHosts=localhost|127.0.0.1|*.local|*.svc|*.cluster.local
EOF
            log_ok "  gradle.properties 代理已配置 ($PROXY_HOST:$PROXY_PORT)"
        else
            log_info "  未检测到代理环境变量，跳过代理配置"
        fi
    fi
fi
echo ""

# ──────────────────────────────────────────────
# Step 4b: 配置 Gradle Wrapper（使用最快镜像）
# ──────────────────────────────────────────────
log_info "[4b/7] 配置 Gradle Wrapper..."
GRADLE_WRAPPER_DIR="$PROJECT_DIR/gradle/wrapper"
GRADLE_WRAPPER_PROP="$GRADLE_WRAPPER_DIR/gradle-wrapper.properties"
mkdir -p "$GRADLE_WRAPPER_DIR"

GRADLE_DIST_FILE="gradle-${GRADLE_VERSION}-bin.zip"

# 选择最快可用镜像
log_info "  正在测试 Gradle 镜像速度..."
SELECTED_GRADLE_MIRROR=$(select_fastest_mirror "$GRADLE_DIST_FILE" "${GRADLE_MIRRORS[@]}")

if [ -z "$SELECTED_GRADLE_MIRROR" ]; then
    SELECTED_GRADLE_MIRROR="https://services.gradle.org/distributions"
    log_warn "  所有镜像不可达，使用官方源"
fi

GRADLE_DIST_URL="${SELECTED_GRADLE_MIRROR}/${GRADLE_DIST_FILE}"
GRADLE_DIST_URL_ESCAPED=$(echo "$GRADLE_DIST_URL" | sed 's/:/\\:/g')

cat > "$GRADLE_WRAPPER_PROP" << EOF
#APK build_env.sh v2.0 auto-generated
#Gradle 镜像源: $(basename $SELECTED_GRADLE_MIRROR)
distributionBase=GRADLE_USER_HOME
distributionUrl=${GRADLE_DIST_URL_ESCAPED}
distributionPath=wrapper/dists
zipStorePath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
EOF
log_ok "  Gradle Wrapper 已配置 (v$GRADLE_VERSION, 镜像: $(basename $SELECTED_GRADLE_MIRROR))"

# 确保 gradlew 可执行
chmod +x "$PROJECT_DIR/gradlew" 2>/dev/null || true
log_ok "  gradlew 权限已设置"

# 预下载 Gradle 发行版到缓存（避免 gradlew 下载超时）
log_info "  预下载 Gradle 发行版到缓存..."
GRADLE_CACHE_DIST_DIR="$HOME/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin"
if [ ! -d "$GRADLE_CACHE_DIST_DIR" ] || [ -z "$(ls -A "$GRADLE_CACHE_DIST_DIR" 2>/dev/null)" ]; then
    GRADLE_TMP_ZIP="/tmp/${GRADLE_DIST_FILE}"
    if download_from_mirrors "$GRADLE_TMP_ZIP" "$GRADLE_DIST_FILE" "${GRADLE_MIRRORS[@]}"; then
        # Gradle wrapper 使用 MD5 的 base-36 编码作为缓存目录名
        # 详见 wrapper PathAssembler.getHash(): BigInteger(1, md5).toString(36)
        if command -v python3 &>/dev/null; then
            HASH=$(python3 -c "
import hashlib
m = hashlib.md5('$GRADLE_DIST_URL'.encode()).digest()
v = int.from_bytes(m, 'big')
digits = '0123456789abcdefghijklmnopqrstuvwxyz'
res = ''
while v > 0:
    v, r = divmod(v, 36)
    res = digits[r] + res
print(res)
")
        elif command -v md5sum &>/dev/null; then
            # 备用方案：用 perl 计算 base-36
            HASH=$(echo -n "$GRADLE_DIST_URL" | md5sum | awk '{print $1}')
            HASH=$(perl -e '
                use bigint;
                my $hex = shift;
                my $num = hex($hex);
                my @digits = (0..9, "a".."z");
                my $res = "";
                while ($num > 0) {
                    $res = $digits[$num % 36] . $res;
                    $num = int($num / 36);
                }
                print $res;
            ' "$HASH" 2>/dev/null) || HASH=""
        else
            HASH=""
        fi

        if [ -n "$HASH" ]; then
            CACHE_DIR="$GRADLE_CACHE_DIST_DIR/$HASH"
            mkdir -p "$CACHE_DIR"
            cp "$GRADLE_TMP_ZIP" "$CACHE_DIR/"
            (cd "$CACHE_DIR" && unzip -qo "$GRADLE_DIST_FILE" 2>/dev/null) || true
            log_ok "  Gradle 发行版已预缓存"
        else
            log_warn "  无法计算缓存 hash，跳过预缓存"
        fi
        rm -f "$GRADLE_TMP_ZIP"
    else
        log_warn "  Gradle 预下载失败，gradlew 首次运行时会自动下载"
    fi
else
    log_ok "  Gradle 缓存已存在，跳过预下载"
fi
echo ""

# ──────────────────────────────────────────────
# Step 5: 配置仓库镜像（国内多镜像源）
# ──────────────────────────────────────────────
log_info "[5/7] 配置仓库镜像（腾讯云/阿里云）..."

if $SKIP_MIRRORS; then
    log_info "  (--skip-mirrors) 跳过镜像配置"
else
    # 清理旧的 init.gradle（避免与 FAIL_ON_PROJECT_REPOS 冲突）
    rm -f "$HOME/.gradle/init.gradle"

    # --- 配置 build.gradle ---
    BUILD_GRADLE="$PROJECT_DIR/build.gradle"
    if grep -q "maven.aliyun.com/repository/google" "$BUILD_GRADLE" 2>/dev/null; then
        log_ok "  build.gradle 镜像已配置，跳过"
    else
        if [ -f "$BUILD_GRADLE" ]; then
            cp "$BUILD_GRADLE" "${BUILD_GRADLE}.bak.$(date +%s)"

            MIRROR_BLOCK=""
            for repo in "${MAVEN_MIRRORS_BUILD[@]}"; do
                MIRROR_BLOCK+="        ${repo}"$'\n'
            done
            MIRROR_BLOCK+="        google()"$'\n'
            MIRROR_BLOCK+="        mavenCentral()"

            BACKUP_FILE=$(ls -t "${BUILD_GRADLE}.bak."* 2>/dev/null | head -1)
            awk -v mirrors="$MIRROR_BLOCK" '
                /^[[:space:]]*google\(\)/ && !done {
                    print mirrors
                    done=1
                    next
                }
                /^[[:space:]]*mavenCentral\(\)/ && !done {
                    print mirrors
                    done=1
                    next
                }
                { print }
            ' "$BACKUP_FILE" > "$BUILD_GRADLE" 2>/dev/null || {
                log_warn "  build.gradle 镜像配置失败，已恢复原文件"
                cp "$BACKUP_FILE" "$BUILD_GRADLE"
            }
            log_ok "  build.gradle 镜像已配置（阿里云+腾讯云+华为云）"
        else
            log_warn "  build.gradle 不存在，跳过"
        fi
    fi

    # --- 配置 settings.gradle ---
    SETTINGS_GRADLE="$PROJECT_DIR/settings.gradle"
    if grep -q "maven.aliyun.com/repository/gradle-plugin" "$SETTINGS_GRADLE" 2>/dev/null; then
        log_ok "  settings.gradle 镜像已配置，跳过"
    else
        if [ -f "$SETTINGS_GRADLE" ]; then
            cp "$SETTINGS_GRADLE" "${SETTINGS_GRADLE}.bak.$(date +%s)"

            SM_MIRROR_BLOCK=""
            for repo in "${MAVEN_MIRRORS_SETTINGS[@]}"; do
                SM_MIRROR_BLOCK+="        ${repo}"$'\n'
            done
            SM_MIRROR_BLOCK+="        gradlePluginPortal()"$'\n'
            SM_MIRROR_BLOCK+="        google()"$'\n'
            SM_MIRROR_BLOCK+="        mavenCentral()"

            LATESTSETBACK=$(ls -t "${SETTINGS_GRADLE}.bak."* 2>/dev/null | head -1)
            awk -v mirrors="$SM_MIRROR_BLOCK" '
                /^[[:space:]]*gradlePluginPortal\(\)/ && !done {
                    print mirrors
                    done=1
                    next
                }
                /^[[:space:]]*google\(\)/ && !done {
                    print mirrors
                    done=1
                    next
                }
                /^[[:space:]]*mavenCentral\(\)/ && !done {
                    print mirrors
                    done=1
                    next
                }
                { print }
            ' "$LATESTSETBACK" > "$SETTINGS_GRADLE" 2>/dev/null || {
                log_warn "  settings.gradle 镜像配置失败，已恢复原文件"
                cp "$LATESTSETBACK" "$SETTINGS_GRADLE"
            }
            log_ok "  settings.gradle 镜像已配置（阿里云+腾讯云+华为云）"
        else
            log_warn "  settings.gradle 不存在，跳过"
        fi
    fi
fi
echo ""

# ──────────────────────────────────────────────
# Step 6: 验证编译环境
# ──────────────────────────────────────────────
log_info "[6/7] 验证编译环境..."
echo ""

ERRORS=0

# Java
if java -version 2>&1 | grep -qE "version \"(11|17|21)"; then
    log_ok "  Java: $(java -version 2>&1 | head -1)"
else
    log_err "  Java: 未安装或版本不兼容（需要 JDK 11/17）"
    ERRORS=$((ERRORS + 1))
fi

# Gradle wrapper
if [ -x "$PROJECT_DIR/gradlew" ]; then
    log_ok "  gradlew: 已就绪"
else
    log_err "  gradlew: 不存在"
    ERRORS=$((ERRORS + 1))
fi

# local.properties
if [ -f "$PROJECT_DIR/local.properties" ]; then
    log_ok "  local.properties: 已生成"
else
    log_err "  local.properties: 不存在"
    ERRORS=$((ERRORS + 1))
fi

# SDK platforms
if [ -d "$SDK_DIR/platforms/$PLATFORM_VERSION" ]; then
    log_ok "  platforms/$PLATFORM_VERSION: 已安装"
else
    log_err "  platforms/$PLATFORM_VERSION: 未安装"
    ERRORS=$((ERRORS + 1))
fi

# Build tools
if [ -d "$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION" ]; then
    log_ok "  build-tools/$BUILD_TOOLS_VERSION: 已安装"
else
    log_err "  build-tools/$BUILD_TOOLS_VERSION: 未安装"
    ERRORS=$((ERRORS + 1))
fi

# 镜像
BUILD_GRADLE="$PROJECT_DIR/build.gradle"
MIRROR_COUNT=0
grep -q "maven.aliyun.com" "$BUILD_GRADLE" 2>/dev/null && MIRROR_COUNT=$((MIRROR_COUNT+1))
grep -q "mirrors.cloud.tencent.com" "$BUILD_GRADLE" 2>/dev/null && MIRROR_COUNT=$((MIRROR_COUNT+1))
grep -q "repo.huaweicloud.com" "$BUILD_GRADLE" 2>/dev/null && MIRROR_COUNT=$((MIRROR_COUNT+1))
if [ $MIRROR_COUNT -gt 0 ]; then
    log_ok "  镜像: 已配置（$MIRROR_COUNT 个国内源）"
else
    log_warn "  镜像: 未配置"
fi

echo ""
echo "============================================"

if [ $ERRORS -eq 0 ]; then
    echo -e "${GREEN}  编译环境恢复成功！${NC}"
    echo ""
    echo "  编译命令:"
    echo "    cd $PROJECT_DIR"
    echo "    ./gradlew assembleRelease"
    echo ""
    echo "  APK 输出路径:"
    echo "    app/build/outputs/apk/release/"
    echo ""
    echo "  Debug 编译:"
    echo "    ./gradlew assembleDebug"
    echo ""
    echo "  可选参数:"
    echo "    --skip-sdk           跳过 Android SDK 安装"
    echo "    --skip-mirrors       跳过镜像配置"
    echo "============================================"
else
    echo -e "${RED}  编译环境恢复不完整（$ERRORS 项缺失）${NC}"
    echo "  请手动安装缺失组件后重新运行此脚本"
    echo "============================================"
    exit 1
fi