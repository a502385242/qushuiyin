#!/usr/bin/env bash
# 一条命令发版：升版本号 → 编 Release 包 → 传 GitHub Release
#
#   bash tools/release.sh 0.3.0
#   bash tools/release.sh 0.3.0 "下载器可用，支持图集"
#
# 前置：local.properties 里有 release.storeFile / storePassword / keyAlias / keyPassword，
#       且 gh 已登录、当前分支没有未提交改动。
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:-}"
NOTE="${2:-}"
if [ -z "$VERSION" ]; then
  echo "用法: bash tools/release.sh <版本号，如 0.3.0> [一句话说明]" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME_17:-/d/devtools/jdk17/jdk-17.0.20.1+1}"
GRADLEW="./gradlew"
[ -x "$GRADLEW" ] || { echo "找不到 gradlew" >&2; exit 1; }

# 工作区必须是干净的，否则打出来的包和仓库里的代码对不上
if [ -n "$(git status --porcelain)" ]; then
  echo "工作区有未提交改动，先提交再发版：" >&2
  git status --short >&2
  exit 1
fi

# versionCode 自增，versionName 用传入值
python - "$VERSION" <<'PY'
import re, sys
from pathlib import Path

new_name = sys.argv[1]
p = Path("app/build.gradle.kts")
s = p.read_text(encoding="utf-8")

m = re.search(r"versionCode\s*=\s*(\d+)", s)
if not m:
    sys.exit("build.gradle.kts 里找不到 versionCode")
new_code = int(m.group(1)) + 1
s = s[:m.start()] + f"versionCode = {new_code}" + s[m.end():]
s = re.sub(r'versionName\s*=\s*"[^"]*"', f'versionName = "{new_name}"', s, count=1)
p.write_text(s, encoding="utf-8")
print(f"版本升到 {new_name} (versionCode {new_code})")
PY

TAG="v$VERSION"
rm -rf app/build/outputs/apk/release
"$GRADLEW" :app:assembleRelease --console=plain | tail -3

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "构建没产出 APK" >&2; exit 1; }

# 没签上名的包不能发：装机时会提示"解析包错误"
SDK="${ANDROID_SDK_ROOT:-/d/devtools/android-sdk}"
APKSIGNER="$SDK/build-tools/34.0.0/apksigner.bat"
[ -f "$APKSIGNER" ] || APKSIGNER="$SDK/build-tools/34.0.0/apksigner"
JAVA_HOME="$JAVA_HOME" "$APKSIGNER" verify "$APK" \
  || { echo "APK 未签名，检查 local.properties 的 release.* 配置" >&2; exit 1; }

mkdir -p dist
OUT="dist/qushuiyin-$VERSION.apk"
cp "$APK" "$OUT"

git add app/build.gradle.kts
git -c user.name="${GIT_NAME:-xy}" -c user.email="${GIT_EMAIL:-510929540@qq.com}" \
  commit -q -m "chore: 发布 $VERSION"
git push -q
git tag -f "$TAG" >/dev/null
git push -f origin "$TAG" >/dev/null

gh release create "$TAG" "$OUT" \
  --title "$TAG" \
  --notes "${NOTE:-详见提交记录。}" 2>/dev/null \
  || gh release upload "$TAG" "$OUT" --clobber

echo
echo "已发布：https://github.com/a502385242/qushuiyin/releases/tag/$TAG"
echo "直链：https://github.com/a502385242/qushuiyin/releases/download/$TAG/qushuiyin-$VERSION.apk"
echo "SHA-256: $(sha256sum "$OUT" | cut -d' ' -f1)"
