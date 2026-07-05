#!/usr/bin/env bash
set -euo pipefail

# 本脚本只管理本地研究资料：拉取目标 APK、运行 jadx、生成 hook 契约报告。
# 输出目录位于 references/，该目录被 git 忽略，不应提交第三方 APK 或反编译代码。

PACKAGE="com.twitter.android"
SERIAL=""
FORCE_DECOMPILE=0
SKIP_PULL=0
SKIP_DECOMPILE=0
ANALYZE_EXISTING=""

usage() {
  cat <<'EOF'
Usage:
  tools/twitter-update/pull-and-analyze.sh [options]

Options:
  --serial SERIAL          Use a specific adb device serial.
  --package PACKAGE        Target package, default: com.twitter.android.
  --force-decompile        Remove and rebuild the jadx output for this version.
  --skip-pull              Reuse the existing references/apks/twitter/<version>.
  --skip-decompile         Reuse the existing jadx output.
  --analyze-existing ID    Analyze an existing version id, for example 12.5.0-release.0_312050000.
  -h, --help               Show this help.

Examples:
  tools/twitter-update/pull-and-analyze.sh --serial 3B15AT00QZD00000
  tools/twitter-update/pull-and-analyze.sh --analyze-existing 12.5.0-release.0_312050000
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      SERIAL="${2:?missing serial}"
      shift 2
      ;;
    --package)
      PACKAGE="${2:?missing package}"
      shift 2
      ;;
    --force-decompile)
      FORCE_DECOMPILE=1
      shift
      ;;
    --skip-pull)
      SKIP_PULL=1
      shift
      ;;
    --skip-decompile)
      SKIP_DECOMPILE=1
      shift
      ;;
    --analyze-existing)
      ANALYZE_EXISTING="${2:?missing version id}"
      SKIP_PULL=1
      SKIP_DECOMPILE=1
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

detect_serial() {
  if [ -n "$SERIAL" ]; then
    return
  fi

  local devices
  devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  local count
  count="$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')"

  if [ "$count" = "1" ]; then
    SERIAL="$(printf '%s\n' "$devices" | sed '/^$/d' | head -n 1)"
    return
  fi

  if [ "$count" = "0" ]; then
    echo "No online adb device found. Connect the device or use --analyze-existing." >&2
  else
    echo "Multiple online adb devices found. Pass --serial explicitly." >&2
    printf '%s\n' "$devices" >&2
  fi
  exit 1
}

adb_shell() {
  adb -s "$SERIAL" shell "$@"
}

pull_apks() {
  local apk_dir="$1"
  mkdir -p "$apk_dir"

  local remote_paths
  remote_paths="$(adb_shell pm path "$PACKAGE" | tr -d '\r' | sed 's/^package://')"
  if [ -z "$remote_paths" ]; then
    echo "No APK path returned for $PACKAGE" >&2
    exit 1
  fi

  printf '%s\n' "$remote_paths" | while IFS= read -r remote; do
    [ -n "$remote" ] || continue
    local name
    name="$(basename "$remote")"
    echo "Pulling $remote -> $apk_dir/$name"
    adb -s "$SERIAL" pull "$remote" "$apk_dir/$name" >/dev/null
  done
}

run_jadx() {
  local base_apk="$1"
  local jadx_dir="$2"

  if [ -d "$jadx_dir/sources" ] && [ "$FORCE_DECOMPILE" -eq 0 ]; then
    echo "Reusing existing jadx output: $jadx_dir"
    return
  fi

  if [ "$FORCE_DECOMPILE" -eq 1 ] && [ -d "$jadx_dir" ]; then
    echo "Removing existing jadx output: $jadx_dir"
    rm -rf "$jadx_dir"
  fi

  mkdir -p "$(dirname "$jadx_dir")"
  echo "Running jadx --no-res on $base_apk"
  set +e
  jadx --no-res -d "$jadx_dir" "$base_apk"
  local jadx_rc="$?"
  set -e

  if [ "$jadx_rc" -ne 0 ]; then
    if [ -d "$jadx_dir/sources" ]; then
      echo "jadx finished with code $jadx_rc, but sources exist. Continuing."
    else
      echo "jadx failed with code $jadx_rc and no sources were produced." >&2
      exit "$jadx_rc"
    fi
  fi
}

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

require_cmd python3

if [ -n "$ANALYZE_EXISTING" ]; then
  VERSION_ID="$ANALYZE_EXISTING"
  VERSION_CODE="${VERSION_ID##*_}"
  VERSION_NAME="${VERSION_ID%_*}"
else
  require_cmd adb
  require_cmd jadx
  detect_serial

  echo "Inspecting $PACKAGE on $SERIAL"
  package_info="$(adb_shell dumpsys package "$PACKAGE" | tr -d '\r')"
  VERSION_NAME="$(printf '%s\n' "$package_info" | sed -n 's/.*versionName=//p' | awk '{ print $1 }' | head -n 1)"
  VERSION_CODE="$(printf '%s\n' "$package_info" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)"

  if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "Failed to parse versionName/versionCode for $PACKAGE" >&2
    exit 1
  fi
  VERSION_ID="${VERSION_NAME}_${VERSION_CODE}"
fi

APK_DIR="$repo_root/references/apks/twitter/$VERSION_ID"
JADX_DIR="$repo_root/references/decompiled/twitter/$VERSION_ID/base-jadx-no-res"
REPORT_DIR="$repo_root/references/decompiled/twitter/$VERSION_ID"

if [ "$SKIP_PULL" -eq 0 ]; then
  pull_apks "$APK_DIR"
else
  echo "Skipping APK pull."
fi

BASE_APK="$APK_DIR/base.apk"
if [ ! -f "$BASE_APK" ]; then
  echo "Missing base APK: $BASE_APK" >&2
  exit 1
fi

if [ "$SKIP_DECOMPILE" -eq 0 ]; then
  require_cmd jadx
  run_jadx "$BASE_APK" "$JADX_DIR"
else
  echo "Skipping jadx decompile."
fi

python3 "$repo_root/tools/twitter-update/analyze_twitter_update.py" \
  --package "$PACKAGE" \
  --version-name "$VERSION_NAME" \
  --version-code "$VERSION_CODE" \
  --apk-dir "$APK_DIR" \
  --jadx-dir "$JADX_DIR" \
  --out-dir "$REPORT_DIR"

echo "Analysis written to:"
echo "  $REPORT_DIR/analysis.json"
echo "  $REPORT_DIR/analysis.md"
