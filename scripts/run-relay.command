#!/bin/bash
# 果映伴机一键启动（macOS）：双击本文件或终端执行。
# 前置：本机已装 ffmpeg 7+（brew install ffmpeg，自带 VVC 解码器）。
# 启动后保持终端窗口开启即为伴机运行中；关闭窗口或 Ctrl-C 即停止。
# 验证：浏览器打开 http://<本机局域网IP>:46521/health，应返回 JSON（ffmpegOk=true）。
# TV 端：播放器「更多 → 播放设置 → 伴机代解码」填 <本机局域网IP>:46521。
set -e
cd "$(dirname "$0")/.."

JDK="$HOME/tools/jdk-17.0.20.1+1/Contents/Home"
JAVA_OPT=""
if [ -d "$JDK" ]; then
  JAVA_OPT="-Porg.gradle.java.installations.paths=$JDK"
fi

echo "── 果映伴机启动中（局域网端口 46521，Ctrl-C 停止）──"
# 已有实例在跑则直接提示退出，避免端口冲突报错。
if curl -s -m 2 "http://127.0.0.1:46521/health" 2>/dev/null | grep -q ffmpegOk; then
  echo "伴机已在运行，无需重复启动。确认页面：http://$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1):46521/health"
  exit 0
fi
exec ./kotlin-tv/gradlew -p relay $JAVA_OPT :relay:run
