#!/usr/bin/env bash
# 在 arm64 Linux runner 上生成最小 rootfs + proot + opencode，打包 rootfs-arm64.tar.gz
# 纯"组装"：不跑 chroot、不用 apt。ubuntu-base 自带 opencode 所需的全部 glibc .so。
set -euo pipefail

ARCH="arm64"
OUT="rootfs-${ARCH}.tar.gz"
ROOT="rootfs-${ARCH}"
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
OC_VER="2.2.6"
OC_TGZ="https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-${OC_VER}.tgz"
PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.4.1/proot"

rm -rf "$ROOT"
mkdir -p "$ROOT"

echo "== 1) 下载并解压 ubuntu-base (arm64 glibc rootfs, ~28MB) =="
curl -fsSL "$BASE_URL" -o /tmp/ubuntu-base.tar.gz
tar -xzf /tmp/ubuntu-base.tar.gz -C "$ROOT"
rm -f /tmp/ubuntu-base.tar.gz

echo "== 2) 基础目录/设备/DNS =="
mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/dev/pts" "$ROOT/tmp" "$ROOT/workspace"
chmod 1777 "$ROOT/tmp"
echo "nameserver 8.8.8.8" > "$ROOT/etc/resolv.conf"
# CA 证书：直接拷宿主的，opencode 联网 https 要用
if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
  mkdir -p "$ROOT/etc/ssl/certs"
  cp -f /etc/ssl/certs/ca-certificates.crt "$ROOT/etc/ssl/certs/ca-certificates.crt"
fi

echo "== 3) 下载 opencode 二进制（宿主解压，塞进 rootfs）=="
mkdir -p "$ROOT/opencode/bin" /tmp/ocpkg
curl -fsSL "$OC_TGZ" -o /tmp/oc.tgz
tar -xzf /tmp/oc.tgz -C /tmp/ocpkg
OC_BIN=$(find /tmp/ocpkg -type f -name opencode -path '*bin*' | head -1)
[ -n "$OC_BIN" ] || { echo "opencode 二进制缺失"; exit 1; }
cp -f "$OC_BIN" "$ROOT/opencode/bin/opencode"
chmod +x "$ROOT/opencode/bin/opencode"
rm -rf /tmp/oc.tgz /tmp/ocpkg
ls -lh "$ROOT/opencode/bin/opencode"

echo "== 4) 下载 proot 静态二进制 =="
mkdir -p "$ROOT/proot/bin"
if ! curl -fsSL "$PROOT_URL" -o "$ROOT/proot/bin/proot"; then
  curl -fsSL "https://github.com/proot-me/proot/releases/download/v5.5.0/proot" -o "$ROOT/proot/bin/proot"
fi
chmod +x "$ROOT/proot/bin/proot"
ls -lh "$ROOT/proot/bin/proot"

echo "== 5) 体积统计 =="
du -sh "$ROOT"

echo "== 6) 打包 $OUT =="
tar -czf "$OUT" -C "$ROOT" .
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
