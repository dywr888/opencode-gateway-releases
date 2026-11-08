#!/usr/bin/env bash
# 在原生 arm64 Linux runner 上生成最小 rootfs + proot + opencode，打包 rootfs-arm64.tar.gz
# 用法: ./build-rootfs.sh
set -euo pipefail

ARCH="arm64"
OUT="rootfs-${ARCH}.tar.gz"
ROOT="rootfs-${ARCH}"
rm -rf "$ROOT"
mkdir -p "$ROOT"

echo "== 安装 debootstrap =="
if ! command -v debootstrap >/dev/null 2>&1; then
  sudo apt-get update
  sudo apt-get install -y debootstrap
fi

echo "== debootstrap minbase (arm64, native) =="
# runner 本身是 arm64，直接原生 bootstrap，无需 qemu
sudo debootstrap --variant=minbase --arch=arm64 bullseye "$ROOT" http://deb.debian.org/debian

echo "== 安装基础工具（chroot 内，原生 arm64）=="
sudo chroot "$ROOT" /bin/bash -c "apt-get update && apt-get install -y --no-install-recommends \
  ca-certificates curl wget zstd libc6 coreutils bash tar gzip"

echo "== 下载 proot 静态二进制 =="
mkdir -p "$ROOT/proot/bin"
PROOT_BIN="https://github.com/proot-me/proot-static/releases/download/2022.12.22/proot-2022.12.22-aarch64-static"
if ! curl -fsSL "$PROOT_BIN" -o "$ROOT/proot/bin/proot"; then
  # 备用：proot.me 源码构建需工具链，这里仅提示
  echo "proot 静态下载失败，请检查网络/链接" >&2
  exit 1
fi
chmod +x "$ROOT/proot/bin/proot"

echo "== 安装 opencode =="
mkdir -p "$ROOT/opencode/bin"
sudo chroot "$ROOT" /bin/bash -c "curl -fsSL https://opencode.ai/v2/install | bash"
if [ -f "$ROOT/root/.opencode/bin/opencode" ]; then
  sudo cp "$ROOT/root/.opencode/bin/opencode" "$ROOT/opencode/bin/opencode"
  sudo chmod +x "$ROOT/opencode/bin/opencode"
fi
ls -l "$ROOT/opencode/bin/opencode" || { echo "opencode 二进制缺失"; exit 1; }

echo "== 清理 =="
sudo chroot "$ROOT" /bin/bash -c "apt-get clean; rm -rf /var/lib/apt/lists/*" || true

echo "== 打包 $OUT =="
# 顶层条目为 ./proot ./opencode ./usr ... 解压到目标目录即可
tar -czf "$OUT" -C "$ROOT" .
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
