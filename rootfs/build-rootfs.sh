#!/usr/bin/env bash
# 在原生 arm64 Linux runner 上生成最小 rootfs + proot + opencode，打包 rootfs-arm64.tar.gz
# 直接用 Ubuntu 官方 ubuntu-base（~28MB 预构建 arm64 glibc rootfs），比 debootstrap 更小更快。
set -euo pipefail

ARCH="arm64"
OUT="rootfs-${ARCH}.tar.gz"
ROOT="rootfs-${ARCH}"
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
rm -rf "$ROOT"
mkdir -p "$ROOT"

echo "== 下载并解压 ubuntu-base (arm64 glibc rootfs) =="
curl -fsSL "$BASE_URL" -o /tmp/ubuntu-base.tar.gz
tar -xzf /tmp/ubuntu-base.tar.gz -C "$ROOT"
rm -f /tmp/ubuntu-base.tar.gz

echo "== 准备 chroot 运行环境（原生 arm64，无需 qemu）=="
mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/dev/pts" "$ROOT/tmp"
chmod 1777 "$ROOT/tmp"
# 关键：chroot 内需要真正的 /dev/null 等设备，否则 apt-key/gpg 验证失败
# 复制宿主的 char 设备节点（runner 是 arm64，设备号兼容）
for dev in null zero urandom random full; do
  if [ -e "/dev/$dev" ]; then
    cp -a "/dev/$dev" "$ROOT/dev/$dev" 2>/dev/null || true
  fi
done
# DNS：opencode 联网要用
echo "nameserver 8.8.8.8" > "$ROOT/etc/resolv.conf"
# ubuntu-base 24.04 自带 deb822 的 ubuntu.sources；删除任何旧式 sources.list 避免重复声明冲突
rm -f "$ROOT/etc/apt/sources.list"
mkdir -p "$ROOT/etc/apt/sources.list.d"
cat > "$ROOT/etc/apt/sources.list.d/ubuntu.sources" <<'EOF'
Types: deb
URIs: http://ports.ubuntu.com/ubuntu-ports
Suites: noble noble-updates noble-security
Components: main restricted universe multiverse
Architectures: arm64
EOF

echo "== 安装基础工具（curl/ca-certificates 等）=="
sudo chroot "$ROOT" /bin/bash -c "mkdir -p /tmp && chmod 1777 /tmp && apt-get update && apt-get install -y --no-install-recommends \
  ca-certificates curl wget libc6 coreutils bash tar gzip gnupg" || {
    echo "apt 安装失败"; exit 1
  }

echo "== 下载 proot 静态二进制（官方 proot-me/proot release）=="
mkdir -p "$ROOT/proot/bin"
PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.4.1/proot"
if ! curl -fsSL "$PROOT_URL" -o "$ROOT/proot/bin/proot"; then
  curl -fsSL "https://github.com/proot-me/proot/releases/download/v5.5.0/proot" -o "$ROOT/proot/bin/proot"
fi
chmod +x "$ROOT/proot/bin/proot"
sudo test -f "$ROOT/proot/bin/proot" || { echo "proot 二进制缺失"; exit 1; }
sudo du -h "$ROOT/proot/bin/proot"

echo "== 安装 opencode =="
mkdir -p "$ROOT/opencode/bin"
sudo chroot "$ROOT" /bin/bash -c "mkdir -p /tmp && chmod 1777 /tmp && curl -fsSL https://opencode.ai/v2/install | bash"

echo "== 定位 opencode 二进制（全部 sudo，规避 .opencode 目录 700 权限）=="
FOUND=""
for cand in \
  "$ROOT/root/.opencode/bin/opencode" \
  "$ROOT/usr/local/bin/opencode" \
  "$ROOT/bin/opencode" \
  "$ROOT/usr/bin/opencode" ; do
  if sudo test -f "$cand"; then FOUND="$cand"; break; fi
done
if [ -z "$FOUND" ]; then
  FOUND=$(sudo find "$ROOT" -type f -name opencode -path '*bin*' 2>/dev/null | head -1 || true)
fi
echo "found opencode at: ${FOUND:-<none>}"
if [ -z "$FOUND" ] || ! sudo test -f "$FOUND"; then
  echo "opencode 二进制缺失"; exit 1
fi
sudo cp "$FOUND" "$ROOT/opencode/bin/opencode"
sudo chmod +x "$ROOT/opencode/bin/opencode"
sudo test -f "$ROOT/opencode/bin/opencode" || { echo "拷贝后仍缺失"; exit 1; }
sudo ls -l "$ROOT/opencode/bin/opencode"
sudo du -h "$ROOT/opencode/bin/opencode"

echo "== 清理 =="
sudo chroot "$ROOT" /bin/bash -c "apt-get clean; rm -rf /var/lib/apt/lists/*" || true

echo "== 体积统计 =="
sudo du -sh "$ROOT"

echo "== 打包 $OUT =="
tar -czf "$OUT" -C "$ROOT" .
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
