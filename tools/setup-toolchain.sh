#!/usr/bin/env bash
# 安装隔离的 Android 构建工具链，不触碰系统 JAVA_HOME / PATH / 注册表
# 全部装在 D:/devtools 下，删除该目录即可完全卸载
# 注意：全程不使用 rm -rf 批量删除，重复执行是安全的（幂等）
set -euo pipefail

BASE="/d/devtools"
TMP="$BASE/tmp"
SDK="$BASE/android-sdk"
mkdir -p "$TMP" "$SDK"

JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
SDK_URL="https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.7-bin.zip"

echo "[1/5] 安装 JDK 17"
if [ ! -d "$BASE/jdk17" ] || [ -z "$(ls -A "$BASE/jdk17" 2>/dev/null)" ]; then
  curl -fL --retry 3 --connect-timeout 20 -o "$TMP/jdk17.zip" "$JDK_URL"
  mkdir -p "$BASE/jdk17"
  unzip -q -o "$TMP/jdk17.zip" -d "$BASE/jdk17"
  rm -f "$TMP/jdk17.zip"
fi
JDK_HOME="$(ls -d "$BASE"/jdk17/*/ | head -1)"
JDK_HOME="${JDK_HOME%/}"
echo "      JDK_HOME=$JDK_HOME"

echo "[2/5] 安装 Android commandline-tools"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  if [ ! -f "$TMP/cmdline-tools.zip" ]; then
    curl -fL --retry 3 --connect-timeout 20 -o "$TMP/cmdline-tools.zip" "$SDK_URL"
  fi
  mkdir -p "$SDK/cmdline-tools"
  # zip 内顶层目录就是 cmdline-tools/，直接解到临时目录后 mv 改名，避免批量删除
  unzip -q -o "$TMP/cmdline-tools.zip" -d "$SDK/_ct_tmp"
  mv "$SDK/_ct_tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rmdir "$SDK/_ct_tmp"
fi
echo "      sdkmanager 就绪"

echo "[3/5] 安装 platform-tools / platforms;android-34 / build-tools;34.0.0"
export JAVA_HOME="$JDK_HOME"
if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  # 预置 license 认可记录，避免 sdkmanager 的交互式协议提示在脚本里卡死
  mkdir -p "$SDK/licenses"
  printf '%s\n' \
    24333f8a63b6825ea9c5514f83c2829b004d1fee \
    d56f5187479451eabf01fb78af6dfcb131a6481e \
    > "$SDK/licenses/android-sdk-license"
  printf '%s\n' 84831b9409646a918e30573bab4c9c91346d8abd \
    > "$SDK/licenses/android-sdk-preview-license"

  "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" \
    --sdk_root="$SDK" \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0" < /dev/null \
    >"$TMP/sdkmanager.log" 2>&1 \
    || { echo "sdkmanager 失败，日志尾部："; tail -25 "$TMP/sdkmanager.log"; exit 1; }
fi

echo "[4/5] 安装 Gradle 8.7（用于生成 wrapper）"
if [ ! -d "$BASE/gradle/gradle-8.7" ]; then
  if [ ! -f "$TMP/gradle.zip" ]; then
    curl -fL --retry 3 --connect-timeout 20 -o "$TMP/gradle.zip" "$GRADLE_URL"
  fi
  mkdir -p "$BASE/gradle"
  unzip -q -o "$TMP/gradle.zip" -d "$BASE/gradle"
  rm -f "$TMP/gradle.zip"
fi

echo "[5/5] 校验"
"$JDK_HOME/bin/javac" -version
ls "$SDK/platforms" 2>/dev/null
ls "$SDK/build-tools" 2>/dev/null
"$BASE/gradle/gradle-8.7/bin/gradle" -v 2>&1 | grep -E "^Gradle" | head -1

echo "TOOLCHAIN_OK"
echo "JAVA_HOME=$JDK_HOME"
echo "ANDROID_HOME=$SDK"
echo "GRADLE_HOME=$BASE/gradle/gradle-8.7"
