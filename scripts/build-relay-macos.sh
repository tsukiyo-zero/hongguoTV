#!/bin/bash
# 果映伴机 macOS 一键构建：ffmpeg 捆绑（sha256 校验）+ fatJar + jpackage dmg。
# 产物：relay/backend/build/jpackage/GuoyingRelay-0.2.0.dmg
# 说明：osxexperts 下载在部分网络需代理，可 export https_proxy 后运行或 -Prelay.proxy 传入。
set -e
cd "$(dirname "$0")/.."

JDK="${JDK:-$HOME/tools/jdk-17.0.20.1+1/Contents/Home}"
./kotlin-tv/gradlew -p relay \
  -Porg.gradle.java.installations.paths="$JDK" \
  -Prelay.jdk="$JDK" \
  :relay:jpackageDmg

echo "── 产物目录：$(pwd)/relay/backend/build/jpackage/ ──"
