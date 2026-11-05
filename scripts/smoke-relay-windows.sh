#!/bin/bash
# 果映伴机 Windows 冒烟：msiexec /a 管理员解包（绕开 Windows Installer 安装服务流程）
# → 启动解包出的 GuoyingRelay.exe → /health 断言 ffmpegOk=true → 清理。
# 背景：CI 上 msiexec /qn 静默安装曾挂起且根因未定位，冒烟主路径改走 /a 解包（见 docs/superpowers/specs/2026-11-05-relay-windows-build-design.md）。
# 坑：不能从 bash 直接调 msiexec——GUI 首进程会把工作移交 Installer 服务即退出，
#     bash 拿到的是假退出码（曾见 103），完成状态与文件落地都不可靠；统一经 PowerShell 等待。
# 用法（Git Bash 或 PowerShell 调 Git bash）：
#   scripts/smoke-relay-windows.sh                # 主路径：/a 解包冒烟，默认 relay/backend/build/jpackage/GuoyingRelay-1.0.0.msi
#   scripts/smoke-relay-windows.sh <msi路径>      # 指定 msi
#   scripts/smoke-relay-windows.sh --install      # 追加带 UI 交互安装确认（人工核对真实安装/卸载，需管理员；不进 CI）
set -e
cd "$(dirname "$0")/.."

PORT=46599
MODE=extract
[ "$1" = "--install" ] && { MODE=install; shift; }
MSI="${1:-relay/backend/build/jpackage/GuoyingRelay-1.0.0.msi}"
[ -f "$MSI" ] || { echo "MSI 不存在：$MSI（先跑 :relay:jpackageCompanion）"; exit 1; }
MSI_W="$(cygpath -w "$(pwd)/$MSI")"

# msiexec 统一入口：参数逐个以单引号传给 PowerShell ArgumentList，-Wait 等真实退出码。
run_msiexec() {  # 参数为 msiexec 参数（Windows 形式）；stdout 为退出码
  local args="" a
  for a in "$@"; do args+="'$a',"; done
  args="${args%,}"
  powershell -NoProfile -Command "\$p = Start-Process msiexec.exe -ArgumentList $args -Wait -PassThru; \$p.ExitCode"
}

# 只杀本次冒烟启动的实例（按命令行 --port 匹配），不动用户日常运行的伴机（46521）。
kill_smoke_instance() {
  powershell -NoProfile -Command \
    "Get-CimInstance Win32_Process -Filter \"Name='GuoyingRelay.exe'\" | Where-Object { \$_.CommandLine -like '*--port $PORT*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" \
    >/dev/null 2>&1 || true
}
trap kill_smoke_instance EXIT

health_check() {
  OK=0
  for i in $(seq 1 20); do
    sleep 1
    curl -s -m 2 "http://127.0.0.1:$PORT/health" | grep -q '"ffmpegOk":true' && OK=1 && break
  done
  [ "$OK" = "1" ] || { echo "health 冒烟失败（20s 内未拿到 ffmpegOk=true）"; exit 1; }
  echo "health 冒烟通过：ffmpegOk=true（端口 $PORT）"
}

run_and_check() {  # $1 = 待启动的 exe（msys 路径）
  "$1" --port "$PORT" &
  health_check
  kill_smoke_instance
}

if [ "$MODE" = "install" ]; then
  echo "── 带 UI 交互安装（人工确认真实安装流程）──"
  RC=$(run_msiexec '/i' "$MSI_W")
  [ "$RC" = "0" ] || [ "$RC" = "3010" ] || { echo "安装失败（msiexec 退出码 $RC）"; exit 1; }
  EXE="/c/Program Files/GuoyingRelay/GuoyingRelay.exe"
  [ -f "$EXE" ] || { echo "安装后未找到 $EXE"; exit 1; }
  run_and_check "$EXE"
  read -rp "运行验证通过。回车卸载，Ctrl+C 暂保留："
  RC=$(run_msiexec '/x' "$MSI_W")
  [ "$RC" = "0" ] || [ "$RC" = "3010" ] || echo "卸载返回退出码 $RC（可手动在「应用」里卸载）"
  echo "卸载完成"
  exit 0
fi

# 主路径：/a 管理员解包到临时目录（不触发安装服务，避开挂起点；实测无需管理员）
EXTRACT="$(mktemp -d)"
trap 'kill_smoke_instance; rm -rf "$EXTRACT"' EXIT
echo "── msiexec /a 解包 → $EXTRACT ──"
RC=$(run_msiexec '/a' "$MSI_W" '/qn' "TARGETDIR=$(cygpath -w "$EXTRACT")")
[ "$RC" = "0" ] || [ "$RC" = "3010" ] || { echo "/a 解包失败（msiexec 退出码 $RC）"; exit 1; }
EXE="$(find "$EXTRACT" -name GuoyingRelay.exe | head -1)"
[ -n "$EXE" ] || { echo "解包后未找到 GuoyingRelay.exe"; ls -R "$EXTRACT" | head -30; exit 1; }
run_and_check "$EXE"
echo "── 冒烟完成，已清理解包目录与进程 ──"
