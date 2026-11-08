# OpenCode Android Gateway

把 OpenCode 封装成一个**最小 Android APK**：

- APK 本身只有几 MB（Kotlin 薄壳 + WebView），不含运行环境。
- 首次启动从 GitHub Release 下载 **运行环境包**（`rootfs.tar.zst`：proot + 极简 Ubuntu rootfs + opencode 二进制），解压到 app 私有目录。
- 启动后在本机拉起 `opencode serve`（随机端口 + 随机密码），**WebView 直接加载本地 Web UI**。
- 原生层用同一 `port` + `password` 打 OpenCode 本地 API（`/api/session`、`/api/event` 等），即「app 调用网关」。

> 每台设备安装后应当**各自登录自己的 OpenCode 账号**使用免费额度。共用一个账号违反 OpenCode 条款并会触发限速/封禁。部分免费模型有地区限制。

## 结构

```
app/                       # Android 薄壳（AGP）
rootfs/                    # 运行环境构建脚本（CI 里打 rootfs.tar.zst）
.github/workflows/         # build-apk.yml（复用 android-sdk）+ build-rootfs.yml
```

## 构建

1. `build-rootfs.yml`：在 x64/arm64 Linux runner 上用 debootstrap 生成 minbase rootfs + proot + opencode，打包 `rootfs.tar.zst` 到 Release。
2. `build-apk.yml`：用 android-sdk 编译并签名 APK，产出 `app-release.apk`。

## 运行（本机自测思路）

需要一台有 root 或普通 Linux 的机器跑 proot；手机端由 APK 内的 `libproot` + `rootfs` 完成，无需额外工具。

详见各目录下的脚本与 workflow。
