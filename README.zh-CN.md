# opentvcast

[English](README.md) | **简体中文**

opentvcast 是一款免费、开源、无广告的投屏**接收端**，面向 Android TV 与 Fire TV。
它让 macOS 或 iOS/iPadOS 设备把屏幕与声音直接投到电视上——不需要 Apple TV，不需要
投屏棒，也不需要任何账号。

```
 macOS（Monterey 及以上）        Android TV / Fire TV
 iOS / iPadOS（16 及以上）       ┌──────────────────────┐
 ┌────────────────┐  AirPlay  │                      │
 │  [你的屏幕]     │ ────────► │  [电视屏幕]           │
 │                │           │                      │
 └────────────────┘           └──────────────────────┘
      点击 AirPlay →               opentvcast
      选择你的电视 →               （本应用）
      完成。✓
```

---

## 范围 —— v1 包含什么

opentvcast 有意只实现**两种**协议：

| 协议 | 状态 |
|---|---|
| **AirPlay 2**（屏幕镜像、音频、视频、照片、DACP 反向遥控） | 已实现 |
| **DLNA / UPnP AV MediaRenderer** | 已实现 —— 见下文 |

**Miracast 与 Google Cast 不属于 v1。** 这是范围决策，不是遗漏：

- **Google Cast** 是唯一需要引入 Google Play Services 的功能。去掉它，两个 flavor
  的依赖就完全一致，基于 View 的界面在完全没有 Play Services 的 Fire TV 上也无需降级。
- **Miracast** 需要 Wi-Fi Direct（`WifiP2pManager`）以及 WFD/MPEG-TS 解码。在
  Wi-Fi Direct 支持程度因厂商而异的电视硬件上，它是"在我这儿能用"类问题报告的最大
  来源，而收益有限——多数发送端在同时可用时更倾向 AirPlay 或 Cast。

两者都是 v2 的候选。协议层就是为此而设计的：新增一种协议 = 新模块 + 一次 `register()`
调用，不需要修改每一个界面——见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

### DLNA 状态

`:dlna` 模块实现了 UPnP AV MediaRenderer：SSDP 发现、在分配到的端口上提供设备描述与
各服务 SCPD、通过 SOAP 提供 `AVTransport` / `RenderingControl` / `ConnectionManager`，
以及 GENA 事件订阅。它**尚未与真实的 Windows 或 Android 控制点联调过**——协议逻辑有
单元测试，socket 循环与播放器没有（见 `docs/ARCHITECTURE.md` → Known gaps）。

---

## 功能

### AirPlay 2（已实现）

- macOS 12+ 与 iOS/iPadOS 16+ 的屏幕镜像 —— H.264 解码
- FairPlay 会话密钥交换（`fp-setup` v2/v3，以及旧式 `rsaaeskey`），通过原生 `libplayfair`
- HomeKit 风格配对（Ed25519/X25519）**与**旧式 SRP PIN 配对，重复失败后锁定
- 镜像音频：AAC-ELD、AAC-LC、ALAC —— 音频与视频可独立启停
- 系统音频串流（ALAC，未加密）—— 应用音频最可靠的路径
- 视频 URL 模式（`/play`）与传输控制（播放 / 暂停 / 拖动 / 停止）
- 正在播放元数据（DMAP）与专辑封面浮层
- DACP 反向遥控 —— 用电视遥控器控制发送端播放
- NTP 时钟同步与 UDP 音频重传（丢包恢复）
- 照片接收 —— iOS 照片 App 推送的 JPEG/PNG，全屏显示

### 应用与平台

- Android TV / Fire TV 应用外壳：前台服务、状态界面、按协议划分的状态卡片
- 设置项：设备显示名、镜像分辨率、镜像音频、PIN 鉴权、视频 URL 模式、竖屏电视（电视竖装）、
  开机自启、调试信息浮层
- 两个 flavor：Google TV（Android 10+）与 Fire TV（Android 7.1+）
- 无广告、无统计、自身不发起任何请求：流量都来自会话本身——局域网的发现与媒体，以及发送端
  明确交给我们的媒体 URL
- 上述媒体请求允许明文 HTTP：主机由发送端（或 DLNA 控制点）决定，不由我们决定——见
  `app/src/main/res/xml/network_security_config.xml`
- GPLv3，提供完整源码

---

## opentvcast 不做什么

- **FairPlay 保护的内容**（Apple TV+、iTunes 购买内容、Netflix、Disney+）——那是
  Apple 的内容 DRM，与上面的会话密钥交换无关。任何开源接收端都放不了，本项目也不尝试。
- **Apple Music 应用内音频** —— 在所有 AirPlay 路径上都被保护。改为转发 Mac 的系统
  音频输出，那条路径是可用的。
- **缓冲音频（AirPlay 2 stream type 103）** —— 已接受，但尚未回放。
- **云端或远程串流** —— 仅限局域网。
- **Miracast / Google Cast** —— 超出 v1 范围（见上文）。

---

## 环境要求

**电视端**

- Google TV / Android TV（Android 10+）或 Amazon Fire TV（Android 7.1+）
- 与发送端处于同一网络
- 已启用 ADB（Google TV）或允许侧载（Fire TV）

**Mac / iPhone / iPad 端**

- macOS 12（Monterey）及以上，或 iOS/iPadOS 16 及以上
- 与电视处于同一网络

**网络**

- 两台设备在同一子网 —— 普通家用路由器即可
- 组播 / mDNS 不得被阻断
- 强烈建议 5 GHz Wi-Fi 或有线网络

---

## 从源码构建

目前没有发布二进制版本，请从源码构建。

### 前置条件

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | Kotlin 与 AGP 均以 17 为目标 |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0` | `compileSdk 35` |
| Android NDK | `28.2.13676358` | `libplayfair.so`、`libalac.so` |
| CMake | `3.22.1` | `:airplay` 的原生构建 |

用 `local.properties`（已被 git 忽略）告诉 Gradle SDK 的位置：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 构建

```bash
git clone <this repository>
cd opentvcast

# Google TV / Android TV：
./gradlew :app:assembleGoogletvDebug

# Fire TV：
./gradlew :app:assembleFiretvDebug
```

APK 输出在 `app/build/outputs/apk/<flavor>/debug/`。

### 测试

```bash
# 全部模块。优先使用这些按模块划分的任务，而不是单个聚合任务 —— 见 docs/TESTING.md。
./gradlew :core:test :platform:testDebugUnitTest :airplay:testDebugUnitTest \
  :test-runner:test \
  :app:testGoogletvDebugUnitTest :app:testFiretvDebugUnitTest

# Lint 与两个 APK（同时也是强制模块依赖图的检查）
./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
  :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

手边没有 SDK？无 SDK 的子集仍可运行：

```bash
./gradlew --settings-file settings.verify.gradle.kts :core:test :test-runner:test
```

---

## 安装到电视

### Google TV / Android TV

1. **设置 → 系统 → 关于 → Android TV OS 版本**，连点 7 次解锁开发者选项。
2. **设置 → 系统 → 开发者选项** → 打开 **USB 调试**。
3. 在 **设置 → 网络和互联网** 中记下电视的 IP。
4. 在你的电脑上执行：
   ```bash
   adb connect <TV-IP>
   adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk
   ```

### Amazon Fire TV

1. **设置 → My Fire TV → 关于**，连点 **Build** 7 次。
2. **设置 → My Fire TV → 开发者选项** → 打开 **ADB 调试** 与 **允许未知来源应用**。
3. 在 **设置 → My Fire TV → 关于 → 网络** 中记下 IP。
4. 在你的电脑上执行：
   ```bash
   adb connect <FIRETV-IP>
   adb install app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
   ```

---

## 使用方法

1. 在电视上启动 opentvcast。主界面会显示电视对外广播的名称。
2. 在 Mac 上点击菜单栏的 **AirPlay** 图标——或使用
   **系统设置 → 显示器 → AirPlay 显示器**。
3. 在列表中选择你的电视。
4. 屏幕即出现在电视上。
5. 停止时，在发送端选择 **关闭 AirPlay 镜像**，或在应用/通知中停止服务。

---

## 已知限制

- **早期软件。** AirPlay 2 协议栈已实现并有单元测试，但跨 macOS/iOS 版本的真机验证
  仍在进行中。欢迎提交问题报告。
- **Apple Music 应用内音频无法解密。** macOS 在每条 AirPlay 路径上都施加 FairPlay。
  请改为转发系统音频输出。
- **PIN 鉴权默认关闭。** 关闭时，局域网内任何设备都可以投屏到电视。在共享网络中请在
  设置里打开，默认值如此的原因见 `docs/decisions/`。
- **缓冲音频（type 103）** 已接受但尚未回放。
- **竖屏电视（Portrait TV）旋转的是画面，不是你的电视。** 它用于被手动转了 90° 的电视：
  投屏画面会跟着转正，而 App 界面保持横屏；退出 App 后旋转随之结束。首次真机测试发现旋转被加在了
  一个会忽略它的视图类型上；现在竖屏时画面改由 `TextureView` 承载，并已在模拟器上确认画面确实旋转
  且完整——真机上的电视仍需复验一次。
- **关闭视频 URL 模式并不会让发送端改为镜像。** 它只是拒绝发送端给的 URL；视频 App
  通常会提示"无法在此电视上播放"，需要你自己去开启屏幕镜像。
- **路由器的 AP 隔离或组播过滤** 会导致电视不出现在 AirPlay 列表中，请关闭这些设置。
- 在拥塞的 2.4 GHz Wi-Fi 上可能出现 100 ms 以上的延迟，建议使用 5 GHz 或有线网络。
- **DLNA 尚未在真机上验证过**（见上文）。

当真机出现问题时，请在重启应用前先采集现场：

```bash
tools/collect-device-logs.sh
```

它会把包状态、内存、CPU 以及按进程过滤的 logcat 写入 `device-test-logs/`。可用
`OPENTVCAST_PACKAGE=<applicationId>` 显式指定某个 flavor。

---

## 参与贡献

请先阅读 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md)。简要说明：

- 遵守其中的编码规则（文件长度上限、类级理由注释、测试覆盖）
- 每个 PR 必须通过构建 + 测试 + lint
- 较大的改动请先开 issue 讨论

有用的入口：[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) 讲模块划分与数据流，
[`docs/TESTING.md`](docs/TESTING.md) 讲测了什么、没测什么，
[`docs/decisions/`](docs/decisions/) 是各条架构决策记录（ADR）。

---

## 许可证

**GNU General Public License v3.0** —— 全文见 [`LICENSE`](LICENSE)，第三方归因见
[`NOTICE`](NOTICE)。

opentvcast 采用 GPL-3.0，是因为它打包了 `playfair` —— AirPlay 2 镜像所必需的、
逆向实现的 FairPlay 会话密钥组件。所有公开的 FairPlay 实现都是 GPL，不存在宽松许可的
替代品。上游只声明"GNU GPL"而未指定版本，因此依据 GPLv2 第 9 条我们选择 GPLv3 ——
这也是与我们所携带的 Apache-2.0 代码相兼容的版本。

**只要包含该组件，opentvcast 就不能改为 MIT、Apache-2.0、LGPL 或闭源**，维护者也不
拥有足以出售商业许可的权利。如果你需要一个宽松许可或商业化的 AirPlay 接收端，必须
向 Apple 取得 AirPlay/MFi 许可并独立实现整套协议栈。

FairPlay Streaming（FPS）*内容* DRM 明确不在范围内，因此受 FairPlay 保护的媒体无法播放。

如果你拿到了二进制构建产物，你有权获得同一许可下的完整对应源码。

---

## 致谢

opentvcast 派生自 [PhairPlay](https://github.com/mazer666/PhairPlay)（Apache-2.0），
本项目所使用的 AirPlay 2 协议栈、原生构建、最初的单元测试套件以及架构决策记录均来自
该项目。这一出处记录在 [`NOTICE`](NOTICE) §2。

另外感谢：

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) ——
  社区维护的 AirPlay 协议文档
- [UxPlay](https://github.com/FDH2/UxPlay) —— 开源 AirPlay 镜像服务端，可作参考实现
- [RPiPlay](https://github.com/FD-/RPiPlay) —— GPL `playfair` 库的来源
  （见 [`NOTICE`](NOTICE) §1）
- [EstebanKubata/playfair](https://github.com/EstebanKubata/playfair) ——
  FairPlay 实现本身（GNU GPL）
- [macosforge/alac](https://github.com/macosforge/alac) —— Apple 开源的 ALAC
  解码器（Apache-2.0）
