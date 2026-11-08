#!/usr/bin/env bash
# 生成可在 Android(arm64) 上运行的 rootfs：ubuntu-base + Termux 版 proot(+loader) + opencode。
# 关键点：
#  1) proot 必须用 Termux 版（bionic/NDK 编译，走 /system/bin/linker64），
#     并带上 libexec/proot/loader 与依赖库 libtalloc/libandroid-shmem。
#  2) 打包用 --hard-dereference 展开硬链接：Android app(untrusted_app) SELinux 禁止 link()，
#     设备端 toybox tar 建硬链会失败。
set -euo pipefail

ARCH="arm64"
OUT="rootfs-${ARCH}.tar.gz"
ROOT="rootfs-${ARCH}"
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
OC_VER="2.2.6"
OC_TGZ="https://registry.npmjs.org/@opencode/cli-linux-arm64/-/cli-linux-arm64-${OC_VER}.tgz"
TERMUX_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"

rm -rf "$ROOT"
mkdir -p "$ROOT"

echo "== 1) ubuntu-base (arm64 glibc rootfs, ~28MB) =="
curl -fsSL "$BASE_URL" -o /tmp/ubuntu-base.tar.gz
tar -xzf /tmp/ubuntu-base.tar.gz -C "$ROOT"
rm -f /tmp/ubuntu-base.tar.gz

echo "== 2) 基础目录/DNS/CA =="
mkdir -p "$ROOT/dev" "$ROOT/proc" "$ROOT/sys" "$ROOT/dev/pts" "$ROOT/tmp" "$ROOT/workspace"
chmod 1777 "$ROOT/tmp"
echo "nameserver 8.8.8.8" > "$ROOT/etc/resolv.conf"
if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
  mkdir -p "$ROOT/etc/ssl/certs"
  cp -f /etc/ssl/certs/ca-certificates.crt "$ROOT/etc/ssl/certs/ca-certificates.crt"
fi

echo "== 3) opencode 二进制 =="
mkdir -p "$ROOT/opencode/bin" /tmp/ocpkg
curl -fsSL "$OC_TGZ" -o /tmp/oc.tgz
tar -xzf /tmp/oc.tgz -C /tmp/ocpkg
OC_BIN=$(find /tmp/ocpkg -type f -name opencode -path '*bin*' | head -1)
[ -n "$OC_BIN" ] || { echo "opencode 二进制缺失"; exit 1; }
cp -f "$OC_BIN" "$ROOT/opencode/bin/opencode"
chmod +x "$ROOT/opencode/bin/opencode"
rm -rf /tmp/oc.tgz /tmp/ocpkg
ls -lh "$ROOT/opencode/bin/opencode"

echo "== 4) Termux proot + loader + 依赖库 =="
mkdir -p "$ROOT/proot/bin" "$ROOT/proot/lib" "$ROOT/proot/libexec"
curl -fsSL "$TERMUX_MIRROR/dists/stable/main/binary-aarch64/Packages" -o /tmp/tpkgs

fn() {
  awk -v pkg="$1" '
    $1=="Package:" && $2==pkg {found=1; next}
    found && $1=="Filename:" {print $2; exit}
    found && $1=="Package:" {found=0}
  ' /tmp/tpkgs
}

extract_deb() { # debfile destdir
  python3 - "$1" "$2" <<'PY'
import sys,os,subprocess,tempfile
deb,dest=sys.argv[1],sys.argv[2]
d=open(deb,'rb').read(); i=8
while i<len(d):
    hdr=d[i:i+60]
    if len(hdr)<60: break
    name=hdr[0:16].decode(errors='replace').strip().rstrip('/')
    size=int(hdr[48:58].decode().strip())
    data=d[i+60:i+60+size]
    if name.startswith('data.tar'):
        ext=name.split('.',1)[1] if '.' in name else 'tar'
        tmp=tempfile.mktemp(suffix='.'+ext)
        open(tmp,'wb').write(data)
        os.makedirs(dest,exist_ok=True)
        subprocess.run(['tar','-xf',tmp,'-C',dest],check=False)
        os.remove(tmp)
    i+=60+size
    if i%2: i+=1
PY
}

for p in proot libtalloc libandroid-shmem; do
  path=$(fn "$p")
  echo "  $p -> $path"
  [ -n "$path" ] || { echo "找不到包 $p"; exit 1; }
  curl -fsSL "$TERMUX_MIRROR/$path" -o "/tmp/$p.deb"
  extract_deb "/tmp/$p.deb" "/tmp/tx_$p"
done

cp -f /tmp/tx_proot/data/data/com.termux/files/usr/bin/proot "$ROOT/proot/bin/proot"
chmod +x "$ROOT/proot/bin/proot"
cp -f /tmp/tx_proot/data/data/com.termux/files/usr/libexec/proot/loader  "$ROOT/proot/libexec/loader"
cp -f /tmp/tx_proot/data/data/com.termux/files/usr/libexec/proot/loader32 "$ROOT/proot/libexec/loader32"
chmod +x "$ROOT/proot/libexec/"*
TL=$(find /tmp/tx_libtalloc -name 'libtalloc.so.*' -type f | head -1)
SH=$(find /tmp/tx_libandroid-shmem -name 'libandroid-shmem.so*' -type f | head -1)
cp -f "$TL" "$ROOT/proot/lib/libtalloc.so.2"
cp -f "$SH" "$ROOT/proot/lib/libandroid-shmem.so"
ls -l "$ROOT/proot/bin/proot" "$ROOT/proot/libexec/loader" "$ROOT/proot/lib/"

echo "== 5) 清理：移除 rootfs 中残留的 .git（防止 VCS 误判）=="
find "$ROOT" -name ".git" -type d -exec rm -rf {} + 2>/dev/null || true

echo "== 6) 体积统计 =="
du -sh "$ROOT"

echo "== 7) 打包 $OUT =="
tar -czf "$OUT" -C "$ROOT" --hard-dereference .
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"

echo "== 8) 校验：不应再有硬链接条目 =="
HL=$(tar -tvf "$OUT" 2>/dev/null | awk '$1 ~ /^h/' | wc -l)
echo "hardlink entries remaining: $HL"