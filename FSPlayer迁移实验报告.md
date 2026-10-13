# FSPlayer 迁移实验报告（TiviMateLite / exp/fsplayer 分支）

- 日期：2026-10-14
- 分支：`exp/fsplayer`（实验分支，**未合并到 main**）
- 结论一句话：**FSPlayer 1.1.1 的 Android AAR 只支持 Vulkan 渲染、没有 OpenGL ES 回退；在没有可用 Vulkan 驱动的设备/模拟器上 `new FSPlayer()` 直接失败并抛出误导性的 `OutOfMemoryError` 导致 App 崩溃。实测环境（MuMu 模拟器）正是如此，故 FSPlayer 在本环境不可用；能否用于真机 S905L3 取决于该机的 Vulkan 驱动，需真机验证。**

---

## 1. 目标

把 TiviMateLite 的播放内核从当前已跑通的 IJKPlayer 换成 FSPlayer（ijkplayer 的活跃维护后继），在实验分支完成迁移、CI 编译、MuMu 实测，并自行找 bug。

## 2. 迁移改动（已完成，仅 Java 层，未改 native）

| 项目 | 内容 |
|---|---|
| 依赖 | `io.github.debugly:fsplayer:1.1.1`（Maven Central，AAR 21.29 MB，含 `armeabi-v7a` + `arm64-v8a`） |
| 包名映射 | `tv.danmaku.ijk.media.player` → `com.debugly.fsplayer.player` |
| 主类映射 | `IjkMediaPlayer` → `FSPlayer`（`IMediaPlayer` 同名） |
| minSdk | 23 → 24（FSPlayer AAR 要求） |
| 删除 | 内联的 `app/src/main/java/tv/danmaku/**`（27 个文件）与 `app/src/main/jniLibs/armeabi-v7a/*.so`（3 个，9.6 MB） |
| 无需改动 | `MainActivity.kt`（仅使用 `PlayerManager` 封装 API） |

改动文件：
- `app/build.gradle.kts`：minSdk→24、加依赖、注释
- `app/src/main/java/com/tivimatelite/player/PlayerManager.kt`：import + 类型 + 全套 setOption 常量前缀
- `app/src/test/java/com/tivimatelite/player/PlayerManagerSourceTest.kt`：断言 `FSPlayer` / `com.debugly.fsplayer.player`
- `.github/workflows/android.yml`：push 触发增加 `exp/**`

**FSPlayer 与 IJK 的 Java API 高度一致**（`setOption` 分类常量 `OPT_CATEGORY_FORMAT/CODEC/SWS/PLAYER`、`native_setLogLevel`/`IJK_LOG_WARN`、`setSurface`/`setDisplay`、监听器 `setOnPreparedListener(IMediaPlayer.OnPreparedListener)` 等），因此 Java 侧迁移是「改包名 + 改类名」级别，工作量很小。

CI 编译：**通过**（run `38440700049`，2m17s，commit `6f84d26`）。

## 3. 实测结果：崩溃（根因定位）

环境：MuMu 12，Android 12 / API 32 / x86_64（含 armeabi-v7a 转译）/ 4 GB。

安装启动后 App **立即崩溃**。关键日志：

```
I/FSPlayer: fsplayer:1.1.1-dev-1.1.1-beta-3-2-g5cbc32d5
D/FSPlayer: FSPlayer_native_setup
E/vulkan  : Load vulkan library directly. so ...
E/FSPlayer: SDL_VoutAndroid_CreateForVulkan: create renderer failed
D/FSPlayer: ijkmp_dec_ref(): ref=0
D/FSPlayer: ijkmp_shutdown_l()
E/AndroidRuntime: FATAL EXCEPTION: main
E/AndroidRuntime: java.lang.OutOfMemoryError: mpjni: native_setup: ijkmp_create() failed
    at com.debugly.fsplayer.player.FSPlayer.native_setup(Native Method)
    at com.debugly.fsplayer.player.FSPlayer.initPlayer(FSPlayer.java:374)
    at com.debugly.fsplayer.player.FSPlayer.<init>(FSPlayer.java:354)
    at com.tivimatelite.player.PlayerManager.createPlayer(PlayerManager.kt:120)
```

**因果链**：`new FSPlayer()` → `native_setup` → `ijkmp_create()` → 创建视频输出 `SDL_VoutAndroid_CreateForVulkan` 失败（返回 NULL）→ `ijkmp_create` 失败 → `native_setup` 抛 **`OutOfMemoryError`**（信息严重误导：并非内存不足，而是 Vulkan 渲染器创建失败）→ 崩溃。

### 为什么 MuMu 上 Vulkan 不可用
- `pm list features` **无 `android.hardware.vulkan.*`**。
- EGL 驱动只有 `/system/lib*/egl/libEGL_emulation.so`（GLES 模拟）。
- 无 `/vendor/lib*/hw/vulkan.*.so`（无厂商 Vulkan HAL）。
- 系统虽带 `/system/lib/libvulkan.so`，但转译后的 ARM 进程无法真正创建可用的 Vulkan 渲染器。

## 4. 为什么这不是「选项没配对」——FSPlayer 从根本上依赖 Vulkan

对 `libfsplayer.so`（armeabi-v7a）做字符串/符号分析：

- **视频输出工厂只有 `SDL_VoutAndroid_CreateForVulkan`**，不存在任何 `SDL_VoutAndroid_CreateForGLES*` / `CreateForAndroid`。
- 二进制内只有 `fs_vulkan_renderer_*` 一族函数，没有 GLES 渲染器。
- 官方 README 明示 Android 视频输出为 **Vulkan**（iOS/macOS/tvOS 为 Metal）。
- 源码 CMake 的 `-DfsNoVulkan=1` **并不是 GLES 回退**，其注释写明：让 `SDL_VoutAndroid_CreateForVulkan` 直接返回 NULL（= 无画面），仅用于二分排查崩溃。

**即：Android 版 FSPlayer 不存在 GLES 渲染路径。** 没有可用 Vulkan 驱动的设备，无论如何都出不了画面。

## 5. 加固与复测（已提交 `a6bc9b5`）

即便本环境不可用，也修掉「构造失败即崩溃」这一硬伤：

- `PlayerManager.getPlayer()`：用 `runCatching` 兜住 `Error`（含 `OutOfMemoryError`），失败时记日志并经 `events.onError(...)` 上报，返回 `null`；`play()` 对 `null` 直接返回。
- 复测（run `38445498068`）：**进程存活**，无 `FATAL EXCEPTION`、无 `Process: com.tivimatelite has died`。日志出现：
  ```
  PlayerManager: FSPlayer 创建失败（设备可能无可用 Vulkan 渲染器）
  PlayerManager: play() aborted: FSPlayer 不可用，url=...
  ```

**次要遗留问题**：`getPlayer` 失败上报 `onError` → `channelSwitcher` 切源 → `play()` → `getPlayer()` 失败……形成递归重试（本次 13 次后随源耗尽停止）。建议增加「播放器不可用」熔断标志，避免重试风暴（未做，留待后续）。

## 6. 结论与建议

1. **main 分支保持 IJK（OpenGL ES，已在 MuMu 真跑通）不动。** FSPlayer 迁移仅保留在 `exp/fsplayer` 实验分支。
2. **不要把 FSPlayer 当作本项目目标设备（晶晨 S905L3）的安全替代**：其 Android 实现 Vulkan-only。S905L3（Mali-G31）理论支持 Vulkan 1.1，但廉价 Amlogic 盒子常缺 Vulkan HAL——**必须先在真机上验证 Vulkan 可用**（最小 `vkCreateInstance` 探测）再决定。
3. 若确实想用 FSPlayer：
   - 先真机验证 Vulkan；
   - 自建 AAR 走源码：`./FFToolChain/main.sh install -p android -l ffmpeg8 && cd android && ./gradlew :fsplayer:assembleRelease`（需 NDK 27.3，重且慢）；
   - 注意 `fsNoVulkan` 不提供 GLES 回退，若目标机无 Vulkan 则需自行实现 GLES 渲染或放弃。
4. 可向上游反馈两点：**（a）** 构造失败抛 `OutOfMemoryError` 属误导，建议改为具名异常（如 `IllegalStateException("Vulkan renderer unavailable")`）；**（b）** 建议提供 GLES 回退或 `hasVulkan()` 探测 API。

## 7. 复现步骤

```
# 1) 构建（CI）
git push origin exp/fsplayer            # 触发 .github/workflows/android.yml

# 2) 安装（MuMu）
adb -s 127.0.0.1:16384 uninstall com.tivimatelite
adb -s 127.0.0.1:16384 install -r app-debug.apk
adb -s 127.0.0.1:16384 logcat -c
adb -s 127.0.0.1:16384 shell monkey -p com.tivimatelite -c android.intent.category.LAUNCHER 1

# 3) 观察
adb -s 127.0.0.1:16384 logcat -d | findstr /C:"SDL_VoutAndroid_CreateForVulkan" /C:"OutOfMemoryError" /C:"FATAL"
```

## 8. 相关提交

| commit | 说明 |
|---|---|
| `6f84d26` | feat(player): migrate playback core from inline IJK to FSPlayer |
| `a6bc9b5` | fix(player): tolerate FSPlayer construction failure on Vulkan-less devices |

CI：`38440700049`（迁移，绿）；`38445498068`（加固，绿）。
