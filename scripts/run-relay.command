#!/bin/bash
# 果映伴机一键启动（macOS）：双击本文件或终端执行。
# 前置：本机已装 ffmpeg 7+（brew install ffmpeg，自带 VVC 解码器）。
# 行为：自动结束已在运行的旧伴机实例（仅限伴机自身进程），再启动新实例；
#       终端窗口开着即在跑，关窗口/Ctrl-C 停止。
# 验证：浏览器打开 http://<本机局域网IP>:46521/health，应返回 JSON（ffmpegOk=true）。
# TV 端：播放器「更多 → 播放设置 → 伴机代解码」填 <本机局域网IP>:46521 与令牌。
set -e
cd "$(dirname "$0")/.."

JDK="$HOME/tools/jdk-17.0.20.1+1/Contents/Home"
JAVA_OPT=""
if [ -d "$JDK" ]; then
  JAVA_OPT="-Porg.gradle.java.installations.paths=$JDK"
fi

# 结束占用 46521 的旧伴机实例：逐个核对命令行确属伴机才杀，防误杀撞端口的其他程序。
OLD_PIDS=$(lsof -nP -t -iTCP:46521 -sTCP:LISTEN 2>/dev/null || true)
if [ -n "$OLD_PIDS" ]; then
  MATCHED=""
  for PID in $OLD_PIDS; do
    CMD=$(ps -p "$PID" -o command= 2>/dev/null || true)
    case "$CMD" in
      *RelayMainKt*|*guoying-relay*)
        MATCHED="$MATCHED $PID"
        ;;
    esac
  done
  if [ -n "$MATCHED" ]; then
    echo "发现旧伴机实例（PID:$MATCHED），正在结束…"
    kill $MATCHED 2>/dev/null || true
    sleep 1
    STILL=$(lsof -nP -t -iTCP:46521 -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$STILL" ]; then
      kill -9 $STILL 2>/dev/null || true
      sleep 1
    fi
  else
    echo "端口 46521 被其他程序占用（非伴机），请手动处理后重试："
    for PID in $OLD_PIDS; do
      echo "  PID $PID: $(ps -p "$PID" -o command= 2>/dev/null)"
    done
    exit 1
  fi
fi

echo "── 果映伴机启动中（局域网端口 46521，Ctrl-C 停止）──"
exec ./kotlin-tv/gradlew -p relay $JAVA_OPT :relay:run
