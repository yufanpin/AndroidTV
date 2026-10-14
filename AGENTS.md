# TiviMateLite — Agent 指南

项目：Android TV IPTV 播放器，专为晶晨 S905L3（2GB RAM、Android 9）低端机顶盒打造。
像素级致敬 TiviMate 架构，RAM 目标 ≤60MB，秒切台，不掉帧。

**播放内核：IJKPlayer**（debugly/ijkplayer k0.8.9 预编译 AAR，OpenGL ES 渲染，不依赖 Vulkan）。
AAR 以二进制形式提交在 `app/libs/ijkplayer-cmake-release.aar`（约 10MB，非 Maven 依赖）。
历史：内核曾经历 Media3/ExoPlayer → IJKPlayer → FSPlayer → 回退 IJKPlayer（FSPlayer 硬依赖
Vulkan，在无 Vulkan 设备上不可用，详见仓库根目录 `FSPlayer迁移实验报告.md`）。

---

## 构建与运行

```bash
# 本地用 wrapper（Windows: gradlew.bat）。CI 里 `gradle` 由 setup-gradle 提供 8.10.2。
.\gradlew.bat assembleDebug --no-daemon --stacktrace

# 单元测试（纯 JVM，无需设备/模拟器）
.\gradlew.bat testDebugUnitTest --no-daemon --stacktrace

# 只跑单个测试类 / 方法
.\gradlew.bat testDebugUnitTest --tests "*ChannelSwitcherTest*"

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

**本地构建前置条件**（否则报 `SDK location not found`）：
- JDK 17
- Android SDK，通过环境变量 `ANDROID_HOME`，或在根目录创建 `local.properties` 写 `sdk.dir=...`
  （`local.properties` 已被 gitignore，不要提交）
- 本机常无 `gradle` 命令，务必用 `gradlew.bat` / `./gradlew`

CI 位于 `.github/workflows/android.yml`，推送 main/master、开 PR 或手动触发，构建 debug APK 并跑单元测试
（CI 命令带 `--info`）。

**构建约束**：
- AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.10.2（wrapper 与 CI 一致，勿随意升级）
- compileSdk = 35, targetSdk = 28（Android 9）, minSdk = 24
- abiFilters = armeabi-v7a + arm64-v8a
- ViewBinding + BuildConfig 均已开启，JVM target 17
- `gradle.properties` 开启 `android.enableJetifier=true`（Glide 等旧库需要）

### 测试约定（重要）

- 测试全部是纯 JVM 单元测试（JUnit4 + `kotlinx-coroutines-test` 虚拟时间），不依赖设备、模拟器、Robolectric。
- **部分测试是"源码断言"测试**：用 `java.io.File("src/main/java/...")` 读取源码/布局/manifest 文本，断言其中包含某些字符串（如 `PlayerManagerSourceTest`、`AdminFeatureSourceTest`、`SplashSourceTest`、`ChannelAdapterTest`、`LocalAdminServerManagerTest`）。因此：
  - 改这些文件的相关代码时，**必须同步更新对应测试里的字面量**，否则重构/改名会莫名挂测试。
  - 这些相对路径以 `app/` 模块目录为工作目录解析；不要在别处运行 JUnit 测试。
- 真正的逻辑测试用注入式构造（如 `ChannelSwitcher(scope, getChannelGroups, ...)`）和虚拟时间 `advanceTimeBy`，测试中不要引入真实 `delay`。

---

## 安装到机顶盒

```bash
adb connect <盒子IP>:5555
adb install -r app-debug.apk
adb shell monkey -p com.tivimatelite 1
```

**ADB over TCP 会在约 3 分钟无活动后断开**。长测时必须每 5s 发 keepalive：
```bash
# 后台 keepalive（长测必加）
powershell -c "while(1){sleep 5; adb shell echo alive > $null}"
```

---

## 核心架构

### 包结构（`com.tivimatelite`）

| 文件 | 职责 |
|---|---|
| `SplashActivity.kt` | 启动闪屏，约 900ms 后跳转 MainActivity |
| `MainActivity.kt` | 唯一 Activity：按键处理、切台/切源逻辑、源故障转移、Ready-stall 监控 |
| `BootReceiver.kt` | 开机自启广播（受 Web 后台开关控制） |
| `model/Channel.kt` | 频道数据类（name, logoUrl, groupName, streamUrl, epgText） |
| `parser/M3U8Parser.kt` | 流式 M3U8 解析器，基于 `BufferedReader.lineSequence()` + Kotlin Flow |
| `player/PlayerManager.kt` | **全局单例 IJKPlayer**（切台不复建，仅 reset + setDataSource + prepareAsync） |
| `player/PlayableHostStore.kt` | 智能线路记忆（可播放域名集合，SharedPreferences `playable_hosts`） |
| `player/PlaybackHistoryStore.kt` | SharedPreferences 持久化上次播放的频道/线路 |
| `switcher/ChannelSwitcher.kt` | 选源、切台/切源防抖、多源故障转移、加载/缓冲超时 |
| `monitor/ReadyStallWatch.kt` | ready-stall 检测、网速监控、心跳、播放列表变更监听 |
| `input/InputHandler.kt` | 数字选台缓冲（900ms 防抖）与频道号悬浮层 |
| `loader/ChannelLoader.kt` | 拉取 + 同名合并为频道组 + 恢复上次选中 |
| `data/RemotePlaylistRepository.kt` | 远程/本机候选源探测与拉取 |
| `ui/ChannelAdapter.kt` | RecyclerView 适配器，DiffUtil + Glide RGB_565 |
| `util/HttpFetcher.kt` | HTTP 拉取工具 |
| `web/LocalAdminServer.kt` | NanoHTTPD Web 后台（端口 5220） |
| `web/LocalAdminServerManager.kt` | 后台生命周期管理 + 播放列表候选 URL |
| `web/PlaylistStore.kt` | 直播源管理模式（内置 / 自定义 / 粘贴） |
| `web/BootPrefs.kt` | 开机自启开关 |
| `web/FileLogStore.kt` | 文件日志（`cacheDir/tivimate_diag.txt`），含未捕获异常处理器 |
| `web/AppLogStore.kt` | 内存环形缓冲区日志（500 行），供 Web 后台查看 |

### 播放器核心设计
```
PlayerManager (object)
  └─ IjkMediaPlayer (lazy singleton, 原生库加载失败永久置位防重试风暴)
       ├─ applyOptions: OPT_CATEGORY_PLAYER（mediacodec* 硬解/framedrop/overlay-format）
       │                OPT_CATEGORY_FORMAT（user_agent/probesize/analyzeduration/reconnect）
       │                OPT_CATEGORY_CODEC（skip_loop_filter）
       ├─ 状态机: IDLE / BUFFERING / READY / ENDED（由 IjkMediaPlayer 回调驱动）
       └─ Events: onError / onStateChanged / onIsPlayingChanged / onVideoSizeChanged
```

### 切台 / 切源逻辑
```
上下键 → 频道切换（pendingSwitchIndex + 300ms 防抖）
左右键 → 当前频道线路切换（pendingSourceDelta + 300ms 防抖，悬浮显示"线路 x/N"）
  └─ 频道多源 → 按顺序尝试，失败自动切下一源（优先记忆中可播放的域名）
  └─ 单源频道 → 重试（最多 3 次，间隔递增 5-15s，最小间隔 12s）
```

### 直播源加载优先级
1. `BuildConfig.PLAYLIST_URL`（远程 URL，在 `app/build.gradle.kts` 中配置）
2. `LocalAdminServer` 候选 URL（`http://<ip>:5220/channels.m3u`、`/playlist`，再回退 `127.0.0.1`）
3. 回退到本地 `assets/channels.m3u`

### 监控机制
- **Buffering failover**：BUFFERING 且播放中持续 35s 自动切源
- **加载超时**：15s 内未 READY 自动切下一源
- **Ready-stall watch**：播放器 READY 但位置与缓冲均停滞超过 300s 触发切源（60s 预热期 + 300s 恢复冷却）
- **播放列表热重载**：配置指纹变化（约 1.2s 轮询）时重新加载频道并保持当前频道
- **网络速度**：每秒更新网速悬浮层
- **心跳**：每 10s 写 FileLogStore 一条 HEARTBEAT，用于确认 app 存活

### 按键映射
| 按键 | 动作 |
|---|---|
| 上下键 | 频道切换（300ms 防抖） |
| 左右键 | 当前频道线路切换（300ms 防抖） |
| 数字键 0-9 | 数字选台（最长 4 位，900ms 无输入后确认） |
| MENU / SETTINGS / INFO | 切换后台诊断信息浮层（4s 自动隐藏） |
| OK / ENTER | 已消费（无动作） |

---

## 关键约束

- **UI 纯 Kotlin + XML ViewBinding，禁止 Jetpack Compose / WebView**
- **纯黑背景（#000000），无动画过渡**
- **单 IJKPlayer 实例，切台严禁销毁重建**——只 `reset()` + `setDataSource` + `prepareAsync`
- **原生库/解码异常只捕获日志，不崩溃**——构造失败置位 `playerCreationFailed` 避免重试风暴
- **Glide 强制 `.override(64, 64)` + `DecodeFormat.PREFER_RGB_565`**
- **列表仅在静止时加载台标**，快速滑动时不加载
- **SurfaceView 直接绑定 `holder.surface`**（绕过 Amlogic phantom-surface bug）

---

## 调试命令

```bash
# 内存
adb shell dumpsys meminfo com.tivimatelite

# CPU/内存实时
adb shell top -o PID,CPU,RES,ARGS | grep tivimatelite

# 日志过滤
adb logcat -s PlayerManager MainActivity ChannelSwitcher ReadyStallWatch

# 取设备端日志文件（跨 ADB 断开持续存在）
adb shell cat /data/data/com.tivimatelite/cache/tivimate_diag.txt

# 强制停止
adb shell am force-stop com.tivimatelite

# 清除数据
adb shell pm clear com.tivimatelite

# 卸载
adb uninstall com.tivimatelite
```

---

## 已知问题

| 优先级 | 问题 | 说明 |
|---|---|---|
| P1 | HLS timeline 不连续 | 部分 `fmt=ts2hls` 实时转码源的 `currentPosition` 卡住，由 300s ready-stall 兜底切源 |
| P2 | 单源频道 | 所有源失败后按 5-15s 递增间隔重试，最多 3 次，之后放弃直到用户切台 |
| P3 | ADB keepalive 自动化 | 长测需要后台 keepalive 脚本 |

---

## 开发者约定

- 仓库根目录下 `要求.txt` 是原始架构需求文档（历史文档，其描述的 ExoPlayer 内核已被 IJKPlayer 取代）
- `TODO_HANDOFF.txt` 是旧交接文件（历史，内容已过时，以本文件与 README 为准）
- **PowerShell 中 `$pid` 是只读自动变量**，永远不能赋值，用 `$currentProcId` 代替
- 修改 `app/build.gradle.kts` 中的 `PLAYLIST_URL` 来切换远程直播源
- Web 后台：`http://<盒子IP>:5220/`（日志实时刷新，2s 轮询）
