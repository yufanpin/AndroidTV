# TiviMateLite P0~P1 优化计划

## TL;DR

> **Quick Summary**: 对 TiviMateLite 实施 4 项 P0~P1 优化（删除 Room 死依赖、拆分 MainActivity 634 行巨类、提取公共 HTTP 工具、添加 Glide 图片加载回退），全部采用 TDD 方式，CI 自动验证。
>
> **Deliverables**:
> - `app/build.gradle.kts` — 移除 room-runtime/room-ktx 死依赖
> - `com.tivimatelite.util.HttpClient.kt` — 公共 HTTP 连接工具
> - `com.tivimatelite.input.InputHandler.kt` — 按键映射 + 数字输入
> - `com.tivimatelite.loader.ChannelLoader.kt` — 频道加载 + 分组 + 恢复
> - `com.tivimatelite.switcher.ChannelSwitcher.kt` — 切台 + 故障转移 + HLS 重试
> - `com.tivimatelite.monitor.ReadyStallWatch.kt` — Ready-stall + 缓冲超时监控
> - `app/src/main/res/drawable/ic_channel_fallback.xml` — Glide 错误回退图标
> - `app/src/test/java/com/tivimatelite/` — 单元测试目录 + 测试用例
> - `.github/workflows/android.yml` — CI 增加 `testDebugUnitTest` 步骤
> - `MainActivity.kt` — 从 634 行缩减至 ~300 行
>
> **Estimated Effort**: Medium (8-12 个任务, 4 个执行波次)
> **Parallel Execution**: YES — 4 波次, Wave 1 最大并行 3 任务
> **Critical Path**: 测试基础设施 → TDD 测试 → 提取类 → MainActivity 整合

---

## Context

### Original Request
用户要求对 TiviMateLite Android TV 项目（Kotlin、Media3 ExoPlayer、NanoHTTPD，目标 S905L3/Android 9/≤60MB RAM）实施优化。

### Interview Summary
**Key Discussions**:
- 范围锁定：仅 P0~P1（删 Room、拆分 MainActivity、提取 HTTP 工具、Glide 回退）
- 测试策略：TDD — 先写行为锁定测试，再重构
- 不包含：R8 混淆（开源自建）、Splash 动画（保留）、P2/P3 项

**Research Findings**:
- Room 在 13 个源文件中零引用，纯死依赖
- MainActivity.kt 634 行，承载 10+ 职责
- RemotePlaylistRepository 与 PlaylistStore 之间有重复的 HttpURLConnection 配置代码
- ChannelAdapter 中 Glide 加载链缺少 `.error()` 回退
- 全项目无任何测试文件

### Metis Review
**Identified Gaps** (addressed):

| 问题 | 决定 |
|---|---|
| 测试框架 | JUnit4 + kotlinx-coroutines-test |
| 拆分粒度 | 4 个类 (ChannelLoader, ChannelSwitcher, InputHandler, ReadyStallWatch) |
| 常量组织 | 随提取的类移动 |
| Glide 回退图标 | 新建简单 vector drawable |
| HTTP 工具返回类型 | HttpURLConnection（调用者自行管理流） |
| PlaylistStore 日志 | 提取时补齐 AppLogStore.w，与 RemotePlaylistRepo 一致 |

---

## Work Objectives

### Core Objective
实施 4 项 P0~P1 代码优化 + 建立测试基础设施，全部 TDD，不改变任何外部行为。

### Concrete Deliverables
- [x] `app/build.gradle.kts` — 移除 room-runtime/room-ktx 行
- [x] `app/src/test/` — 测试目录 + 依赖 + CI 步骤
- [x] `app/src/main/res/drawable/ic_channel_fallback.xml` — 向量图标
- [x] `com.tivimatelite.util.HttpClient.kt` — HTTP 连接工具
- [x] `com.tivimatelite.input.InputHandler.kt` — 按键处理
- [x] `com.tivimatelite.loader.ChannelLoader.kt` — 频道加载
- [x] `com.tivimatelite.switcher.ChannelSwitcher.kt` — 切台逻辑
- [x] `com.tivimatelite.monitor.ReadyStallWatch.kt` — 监控逻辑
- [x] `MainActivity.kt` — 缩减至 ~300 行

### Definition of Done
- `gradle testDebugUnitTest --no-daemon` → ALL PASS
- `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
- GitHub Actions CI 构建 + 测试均绿

### Must Have
- 4 项 P0~P1 优化全部实施
- TDD：每个重构前先写测试
- 测试在 CI 中自动运行

### Must NOT Have (Guardrails)
- 禁止引入 MVP/MVVM/DI(Hilt/Koin)/新 Activity/Fragment/Service
- 禁止更改 PlayerManager 的单例特性
- 禁止移除 Room 以外的任何依赖
- 禁止重命名现有方法或更改签名（除 visible 外）
- 禁止创建接口 + 实现抽象层
- 禁止修改 SplashActivity 动画
- 禁止配置 R8/ProGuard

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: NO (from scratch)
- **Automated tests**: TDD — tests written BEFORE each refactoring
- **Framework**: JUnit4 + kotlinx-coroutines-test
- **CI Integration**: `gradle testDebugUnitTest` added to GitHub Actions

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.omo/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Build verification**: `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
- **Test verification**: `gradle testDebugUnitTest --no-daemon` → ALL PASS
- **CI verification**: `.github/workflows/android.yml` syntax check + dry run

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately — foundation, ALL PARALLEL):
├── Task 1: 测试基础设施 (build.gradle.kts + CI YAML)
├── Task 2: 删除 Room 死依赖
└── Task 3: 创建 Glide 回退 vector drawable

Wave 2 (After Wave 1 — TDD + 小提取, ALL PARALLEL):
├── Task 4: Fix PlaylistStore 静默错误 (补充日志)
├── Task 5: M3U8Parser 行为锁定测试 (TDD)
├── Task 6: 提取 HttpFetcher 工具 + 测试
└── Task 7: ChannelAdapter Glide .error() 回退

Wave 3 (After Wave 2 — MainActivity 拆分, ALL PARALLEL):
├── Task 8: 提取 InputHandler + 测试
├── Task 9: 提取 ChannelLoader + 测试
├── Task 10: 提取 ChannelSwitcher + 测试
└── Task 11: 提取 ReadyStallWatch + 测试

Wave 4 (After Wave 3 — 整合):
└── Task 12: MainActivity 整合为协调器 (~300 lines)

Wave FINAL (After ALL — 4 parallel reviews):
├── F1: Plan compliance audit (oracle)
├── F2: Build + test CI gate (unspecified-high)
├── F3: QA execution (unspecified-high)
└── F4: Scope fidelity check (deep)
-> Present results -> Get explicit user ok

Critical Path: Task 1 → Task 5 → Task 8 → Task 12 → F1-F4 → user ok
Parallel Speedup: ~60% faster than sequential (Wave 1: 3 parallel, Wave 2: 4 parallel, Wave 3: 4 parallel)
Max Concurrent: 4 (Wave 2)
```

### Dependency Matrix

> Format: `{task}: {blocked_by} - {blocks}, {effort_1-5}`

- **1**: - 5, 6, 7, 2
- **2**: - none, 3
- **3**: - 7, 3
- **4**: - none, 2
- **5**: 1 - 8, 3
- **6**: 2, 3, 4, 5 - 8, 9, 10, 11, 2
- **7**: 1, 2, 3, 4 - 8, 9, 10, 11, 2
- **8**: 5, 6, 7 - 12, 3
- **9**: 5, 6, 7 - 12, 3
- **10**: 5, 6, 7 - 12, 3
- **11**: 5, 6, 7 - 12, 3
- **12**: 8, 9, 10, 11 - F1-F4, 4
- **F1-F4**: 12 - user ok, 4

---

## TODOs

- [x] 1. 建立测试基础设施 (build.gradle.kts + CI YAML + 目录结构)

  **What to do**:
  - 在 `app/build.gradle.kts` 的 `dependencies {}` 块中追加：
    ```kotlin
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    ```
  - 创建目录 `app/src/test/java/com/tivimatelite/` 并在其中放一个空占位文件（如 `PlaceholderTest.kt`），确保 Gradle test task 不会因空目录报错
  - 修改 `.github/workflows/android.yml`：在 `Build debug APK` 步骤之后增加：
    ```yaml
    - name: Run unit tests
      run: gradle testDebugUnitTest --no-daemon --stacktrace --info
    ```
  - 本地验证：`gradle testDebugUnitTest --no-daemon` → ALL PASS

  **Must NOT do**:
  - 不要添加 `androidTestImplementation` 或创建 `androidTest/` 目录
  - 不要添加 Robolectric 或 MockK（超出约定范围）
  - 不要修改 `compileSdk`/`targetSdk`/`minSdk` 或其他构建配置

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 纯配置修改 + 目录创建，无逻辑代码
  - **Skills**: `[]`
  - **Skills Evaluated but Omitted**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3)
  - **Blocks**: Tasks 4-12 (all TDD tasks depend on test infra)
  - **Blocked By**: None

  **References**:
  - `app/build.gradle.kts:34-48` — 现有 dependencies 块，追加测试依赖
  - `.github/workflows/android.yml:42-43` — 现有 build 步骤，在其后插入 test 步骤
  - `AGENTS.md:8-10` — 构建命令参考

  **Acceptance Criteria**:
  - [ ] `app/build.gradle.kts` 包含 `testImplementation("junit:junit:4.13.2")`
  - [ ] `app/build.gradle.kts` 包含 `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")`
  - [ ] `app/src/test/java/com/tivimatelite/` 目录存在且非空
  - [ ] `.github/workflows/android.yml` 包含 `gradle testDebugUnitTest` 步骤
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS (0 tests, 0 failures)

  **QA Scenarios**:
  ```
  Scenario: 验证测试依赖已添加
    Tool: Bash (grep)
    Steps:
      1. grep -c "testImplementation" app/build.gradle.kts
    Expected Result: 输出 ≥2 (junit + coroutines-test)
    Evidence: .omo/evidence/task-1-test-deps.txt

  Scenario: CI 配置包含测试步骤
    Tool: Bash (grep)
    Steps:
      1. grep "testDebugUnitTest" .github/workflows/android.yml
    Expected Result: 找到非空匹配
    Evidence: .omo/evidence/task-1-ci-test.txt
  ```

  **Evidence to Capture**:
  - [ ] task-1-test-deps.txt — testImplementation 行数
  - [ ] task-1-ci-test.txt — CI 中 testDebugUnitTest 行

  **Commit**: YES
  - Message: `build(test): add JUnit4 + coroutines-test infra and CI test step`
  - Files: `app/build.gradle.kts`, `.github/workflows/android.yml`, `app/src/test/java/com/tivimatelite/PlaceholderTest.kt`
  - Pre-commit: `gradle testDebugUnitTest --no-daemon`

- [x] 2. 删除 Room 死依赖

  **What to do**:
  - 从 `app/build.gradle.kts` 的 `dependencies {}` 块中删除这两行：
    ```kotlin
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ```
  - 确认全项目无任何 `import androidx.room.*` 或 `androidx.room` 引用
  - `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL

  **Must NOT do**:
  - 不要删除其他依赖
  - 不要修改 `要求.txt`（那是需求文档，不是构建配置）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 删除两行，纯机械操作
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3)
  - **Blocks**: None (independent)
  - **Blocked By**: None

  **References**:
  - `app/build.gradle.kts:42-43` — 待删除的两行
  - `要求.txt:19` — Room 的原始引用（文档，不改）

  **Acceptance Criteria**:
  - [ ] `grep -c "room-runtime" app/build.gradle.kts` → 0
  - [ ] `grep -c "room-ktx" app/build.gradle.kts` → 0
  - [ ] `grep -r "androidx.room" app/src/ --include="*.kt"` → 无输出
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL

  **QA Scenarios**:
  ```
  Scenario: 验证 Room 已从依赖中移除
    Tool: Bash (grep)
    Steps:
      1. grep "room" app/build.gradle.kts
    Expected Result: 无输出（退出码 1）
    Evidence: .omo/evidence/task-2-room-removed.txt

  Scenario: 验证项目无 Room 引用
    Tool: Bash (grep)
    Steps:
      1. grep -r "androidx.room" app/src/ --include="*.kt"
    Expected Result: 无输出
    Evidence: .omo/evidence/task-2-room-imports.txt
  ```

  **Evidence to Capture**:
  - [ ] task-2-room-removed.txt
  - [ ] task-2-room-imports.txt

  **Commit**: YES (groups with Task 3)
  - Message: `chore(deps): remove dead room-runtime/room-ktx dependency`
  - Files: `app/build.gradle.kts`

- [x] 3. 创建 Glide 错误回退 vector drawable

  **What to do**:
  - 创建 `app/src/main/res/drawable/ic_channel_fallback.xml`，内容为简单的灰色圆角矩形轮廓（约 40x40dp）：
    ```xml
    <?xml version="1.0" encoding="utf-8"?>
    <shape xmlns:android="http://schemas.android.com/apk/res/android"
        android:shape="rectangle">
        <solid android:color="#333333" />
        <stroke android:width="1dp" android:color="#555555" />
        <corners android:radius="4dp" />
        <size android:width="40dp" android:height="40dp" />
    </shape>
    ```
  - 确保 `ChannelAdapter.kt` 中后续任务能通过 `R.drawable.ic_channel_fallback` 引用

  **Must NOT do**:
  - 不要创建多个 drawable 变体（ldpi/mdpi/hdpi/xhdpi 等）
  - 不要使用位图（png）— vector/shape drawable 即可

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 创建单文件

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2)
  - **Blocks**: Task 7 (ChannelAdapter fallback)
  - **Blocked By**: None

  **References**:
  - `app/src/main/res/drawable/channel_item_background.xml` — 参考 drawable 风格

  **Acceptance Criteria**:
  - [ ] `Test-Path app/src/main/res/drawable/ic_channel_fallback.xml`
  - [ ] 文件内容为有效的 Android shape drawable XML
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL

  **QA Scenarios**:
  ```
  Scenario: 验证 drawable 文件存在且有效
    Tool: Bash (Test-Path)
    Steps:
      1. Test-Path app/src/main/res/drawable/ic_channel_fallback.xml
    Expected Result: True
    Evidence: .omo/evidence/task-3-drawable-exists.txt
  ```

  **Evidence to Capture**:
  - [ ] task-3-drawable-exists.txt

  **Commit**: YES (groups with Task 2)
  - Message: `chore(deps): remove dead room-runtime/room-ktx dependency`
  - Files: (committed with Task 2)

- [ ] 4. 补齐 PlaylistStore HTTP 错误日志

  **What to do**:
  - 在 `PlaylistStore.loadFromUrl()` 的 `runCatching{}.getOrNull()` 中，于 `.onFailure` 块添加日志记录，与 `RemotePlaylistRepository.loadFromUrl()` 保持一致：
    ```kotlin
    .onFailure {
        AppLogStore.w("PlaylistStore", "Custom source fetch failed for $url", it)
    }
    ```

  **Must NOT do**:
  - 不要改 `RemotePlaylistRepository` 中已有的日志逻辑
  - 不要改变返回类型或错误处理语义（仍然返回 null）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 单文件单行变更

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 5, 6, 7)
  - **Blocks**: None
  - **Blocked By**: None (无需测试基础设施，纯代码变更)

  **References**:
  - `web/PlaylistStore.kt:139-149` — 现有 `loadFromUrl()` 方法
  - `data/RemotePlaylistRepository.kt:95-98` — 参考其 `.onFailure` 日志模式

  **Acceptance Criteria**:
  - [ ] `grep "AppLogStore.w" app/src/main/java/com/tivimatelite/web/PlaylistStore.kt` → 找到新行
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL

  **QA Scenarios**:
  ```
  Scenario: 验证日志行已添加
    Tool: Bash (grep)
    Steps:
      1. Select-String "AppLogStore.w.*PlaylistStore.*fetch" app/src/main/java/com/tivimatelite/web/PlaylistStore.kt
    Expected Result: 找到匹配行
    Evidence: .omo/evidence/task-4-log-added.txt
  ```

  **Evidence to Capture**:
  - [ ] task-4-log-added.txt

  **Commit**: YES
  - Message: `fix(web): add AppLogStore.w to PlaylistStore.loadFromUrl failure path`
  - Files: `app/src/main/java/com/tivimatelite/web/PlaylistStore.kt`
  - Pre-commit: `gradle assembleDebug --no-daemon`

- [ ] 5. M3U8Parser 行为锁定测试 (TDD)

  **What to do**:
  - 创建 `app/src/test/java/com/tivimatelite/parser/M3U8ParserTest.kt`
  - **TDD: 先写测试，再验证现有代码是否通过**（预期：全部通过，因为 parser 功能已稳定）
  - 测试覆盖场景：
    1. 标准 `#EXTINF` 行解析（提取 tvg-name, tvg-logo, group-title, URL）
    2. 简洁格式 `name,http://...` 行解析
    3. 多种 `#EXTINF` 属性排列（部分缺失 tvg-logo、group-title 等）
    4. 空白行和注释行（`#` 开头但非 EXTINF）应被忽略
    5. 无频道的空流 → 空列表
    6. 特殊字符（中文、空格、Unicode）在频道名中
  - 测试工具：JUnit4 + kotlinx-coroutines-test `runTest { ... }`
  - `M3U8Parser.parse()` 返回 `Flow<Channel>`，用 `flow.toList()` 收集为 List 再断言

  **Must NOT do**:
  - 不要修改 `M3U8Parser.kt` 源代码（当前测试只验证现有行为）
  - 不要测试 Android 框架依赖（M3U8Parser 只依赖 `InputStream`，纯 JVM）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 需要理解 Kotlin Flow 测试模式、协程测试调度器
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 4, 6, 7)
  - **Blocks**: Tasks 8-12（测试模式确立后利于后续 TDD）
  - **Blocked By**: Task 1（测试基础设施）

  **References**:
  - `parser/M3U8Parser.kt:10-88` — 完整解析器源码
  - `parser/M3U8Parser.kt:parse()` — 返回 `Flow<Channel>`
  - `model/Channel.kt:3-9` — Channel data class
  - `assets/channels.m3u` — 真实数据样本

  **Test Code Template**:
  ```kotlin
  package com.tivimatelite.parser

  import com.tivimatelite.model.Channel
  import kotlinx.coroutines.flow.toList
  import kotlinx.coroutines.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Test
  import java.io.ByteArrayInputStream

  class M3U8ParserTest {
      @Test
      fun `parse EXTINF line with all attributes`() = runTest {
          val m3u = """
              #EXTM3U
              #EXTINF:-1 tvg-name="CCTV 1" tvg-logo="http://logo" group-title="央视",CCTV 1
              http://example.com/live.m3u8
          """.trimIndent()
          val input = ByteArrayInputStream(m3u.toByteArray())
          val result = M3U8Parser.parse(input).toList()
          assertEquals(1, result.size)
          assertEquals("CCTV 1", result[0].name)
          assertEquals("http://logo", result[0].logoUrl)
          assertEquals("央视", result[0].groupName)
          assertEquals("http://example.com/live.m3u8", result[0].streamUrl)
      }
  }
  ```

  **Acceptance Criteria**:
  - [ ] `app/src/test/java/com/tivimatelite/parser/M3U8ParserTest.kt` 存在
  - [ ] 至少包含 6 个测试用例（覆盖上述 6 个场景）
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS (≥6 tests, 0 failures)
  - [ ] `M3U8Parser.kt` 源代码未被修改

  **QA Scenarios**:
  ```
  Scenario: 运行 M3U8Parser 单元测试
    Tool: Bash
    Steps:
      1. gradle testDebugUnitTest --no-daemon --tests "*M3U8ParserTest*"
    Expected Result: ALL PASS, ≥6 tests
    Evidence: .omo/evidence/task-5-m3u8-tests.txt
  ```

  **Evidence to Capture**:
  - [ ] task-5-m3u8-tests.txt — 测试运行结果

  **Commit**: YES
  - Message: `test(parser): add behavior-locking tests for M3U8Parser`
  - Files: `app/src/test/java/com/tivimatelite/parser/M3U8ParserTest.kt`
  - Pre-commit: `gradle testDebugUnitTest --no-daemon`

- [ ] 6. 提取公共 HttpFetcher 工具 + 测试

  **What to do**:
  - 创建 `app/src/main/java/com/tivimatelite/util/HttpFetcher.kt`，包含顶层函数或 object：
    ```kotlin
    package com.tivimatelite.util

    import java.net.HttpURLConnection
    import java.net.URL

    object HttpFetcher {
        private const val CONNECT_TIMEOUT_MS = 3500
        private const val READ_TIMEOUT_MS = 7000

        fun openConnection(url: String): HttpURLConnection {
            return (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }
        }
    }
    ```
  - **TDD 方式**：
    1. 先写 `HttpFetcherTest.kt`：验证 `openConnection()` 返回正确配置的 `HttpURLConnection`
    2. 再创建 `HttpFetcher.kt` 并使测试通过
  - 修改 `RemotePlaylistRepository.kt` — 替换 `URL(url).openConnection() as HttpURLConnection.apply { ... }` 为 `HttpFetcher.openConnection(url)`
  - 修改 `PlaylistStore.kt` — 同样替换
  - 确认 `X-Playlist-Active` header 读取逻辑在 `RemotePlaylistRepository` 中保持不变（工具只负责连接配置）

  **Must NOT do**:
  - 不要更改两个调用者中连接配置之外的任何代码逻辑
  - 不要引入 OkHttp 或其他 HTTP 库替换 HttpURLConnection
  - 不要改变 `X-Playlist-Active` header 的读取方式

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 涉及 TDD + 跨文件重构，需确保行为一致
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 4, 5, 7)
  - **Blocks**: Tasks 8-12（提取后的类可能需要 HTTP 工具）
  - **Blocked By**: Task 1（测试基础设施）

  **References**:
  - `data/RemotePlaylistRepository.kt:78-98` — 现有 HTTP 连接代码（待替换）
  - `web/PlaylistStore.kt:139-149` — 现有 HTTP 连接代码（待替换）

  **Acceptance Criteria**:
  - [ ] `app/src/main/java/com/tivimatelite/util/HttpFetcher.kt` 存在
  - [ ] `app/src/test/java/com/tivimatelite/util/HttpFetcherTest.kt` 存在
  - [ ] `grep "openConnection.*HttpURLConnection" app/src/main/java/com/tivimatelite/data/RemotePlaylistRepository.kt` → 无匹配
  - [ ] `grep "openConnection.*HttpURLConnection" app/src/main/java/com/tivimatelite/web/PlaylistStore.kt` → 无匹配
  - [ ] `grep "HttpFetcher.openConnection" app/src/main/java/com/tivimatelite/data/RemotePlaylistRepository.kt` → 有匹配
  - [ ] `grep "HttpFetcher.openConnection" app/src/main/java/com/tivimatelite/web/PlaylistStore.kt` → 有匹配
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: 验证 HttpFetcher 文件和测试存在
    Tool: Bash (Test-Path)
    Steps:
      1. Test-Path app/src/main/java/com/tivimatelite/util/HttpFetcher.kt
      2. Test-Path app/src/test/java/com/tivimatelite/util/HttpFetcherTest.kt
    Expected Result: True, True
    Evidence: .omo/evidence/task-6-files-exist.txt

  Scenario: 验证重复代码已移除
    Tool: Bash (grep)
    Steps:
      1. Select-String "openConnection.*as HttpURLConnection" app/src/main/java/com/tivimatelite/data/RemotePlaylistRepository.kt
      2. Select-String "openConnection.*as HttpURLConnection" app/src/main/java/com/tivimatelite/web/PlaylistStore.kt
    Expected Result: 均无输出
    Evidence: .omo/evidence/task-6-duplicates-removed.txt
  ```

  **Evidence to Capture**:
  - [ ] task-6-files-exist.txt
  - [ ] task-6-duplicates-removed.txt

  **Commit**: YES
  - Message: `refactor(util): extract common HttpURLConnection setup to HttpFetcher`
  - Files: `app/src/main/java/com/tivimatelite/util/HttpFetcher.kt`, `app/src/test/java/.../util/HttpFetcherTest.kt`, `app/src/main/java/.../data/RemotePlaylistRepository.kt`, `app/src/main/java/.../web/PlaylistStore.kt`
  - Pre-commit: `gradle testDebugUnitTest --no-daemon && gradle assembleDebug --no-daemon`

- [ ] 7. ChannelAdapter Glide .error() 回退

  **What to do**:
  - 在 `ChannelAdapter.kt` 的 `bindLogo()` 方法中，在 `.apply(LOGO_OPTIONS)` 之后添加 `.error(R.drawable.ic_channel_fallback)`：
    ```kotlin
    fun bindLogo(channel: Channel, loadLogo: Boolean) {
        if (loadLogo && !channel.logoUrl.isNullOrBlank()) {
            requestManager
                .load(channel.logoUrl)
                .apply(LOGO_OPTIONS)
                .error(R.drawable.ic_channel_fallback)  // ← 新增
                .into(binding.channelLogo)
        } else {
            requestManager.clear(binding.channelLogo)
            binding.channelLogo.setImageDrawable(null)
        }
    }
    ```
  - **TDD 方式**：先写 `ChannelAdapterTest.kt` 验证 `LOGO_OPTIONS` 的配置值和回退行为，再修改源代码
  - 注意：需要确保 `R.drawable.ic_channel_fallback` 已经在 Task 3 中创建

  **Must NOT do**:
  - 不要改变 `LOGO_OPTIONS`（`override(64,64)`, `format(RGB_565)`, `dontAnimate()`）
  - 不要添加 `listener()` 或其他 Glide 回调

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 单行添加，模式固定
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 4, 5, 6)
  - **Blocks**: None
  - **Blocked By**: Task 3 (fallback drawable)

  **References**:
  - `ui/ChannelAdapter.kt:96-106` — `bindLogo()` 方法
  - `ui/ChannelAdapter.kt:109-115` — `LOGO_OPTIONS` 常量

  **Acceptance Criteria**:
  - [ ] `grep "\.error\(" app/src/main/java/com/tivimatelite/ui/ChannelAdapter.kt` → 找到 `error(R.drawable.ic_channel_fallback)`
  - [ ] `grep "override(64, 64)" app/src/main/java/com/tivimatelite/ui/ChannelAdapter.kt` → 保持不变
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: 验证 .error() 已添加
    Tool: Bash (grep)
    Steps:
      1. Select-String "\.error\(" app/src/main/java/com/tivimatelite/ui/ChannelAdapter.kt
    Expected Result: 包含 "error(R.drawable.ic_channel_fallback)"
    Evidence: .omo/evidence/task-7-error-added.txt
  ```

  **Evidence to Capture**:
  - [ ] task-7-error-added.txt

  **Commit**: YES
  - Message: `fix(ui): add Glide .error() fallback for channel logo loading`
  - Files: `app/src/main/java/com/tivimatelite/ui/ChannelAdapter.kt`, `app/src/test/java/.../ui/ChannelAdapterTest.kt`
  - Pre-commit: `gradle testDebugUnitTest --no-daemon`

- [ ] 8. 提取 InputHandler（按键映射 + 数字输入）+ 测试

  **What to do**:
  - 创建 `app/src/main/java/com/tivimatelite/input/InputHandler.kt`
  - **TDD**：先写测试覆盖以下行为，再提取类
  - 从 `MainActivity.kt` 提取到 `InputHandler`：
    - `dispatchKeyEvent()` 中的按键分发逻辑
    - `handleNumericKey()`, `keyCodeToDigit()`, `numericInputBuffer`
    - `showChannelNumberOverlay()`（接收外部传入的 View 引用）
    - 相关常量：`NUMERIC_INPUT_COMMIT_MS`, `CHANNEL_NUMBER_HIDE_MS`
  - `InputHandler` 通过构造函数接收：
    - `onChannelRequest: (Int) -> Unit` — 当数字输入确认后回调（不直接引用 MainActivity）
    - `channelNumberText: TextView` 引用用于显示频道号
  - MainActivity 中保留 `dispatchKeyEvent()` 作为入口，但将按键委托给 `InputHandler`

  **Must NOT do**:
  - 不要改变 KeyEvent 常量的数值映射
  - 不要改变 debounce 时间（300ms 切台、900ms 数字提交）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: TDD + 状态提取，需要理解按键映射逻辑
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 9, 10, 11)
  - **Blocks**: Task 12
  - **Blocked By**: Tasks 5, 6, 7（TDD 模式已建立）

  **References**:
  - `MainActivity.kt:125-179` — `dispatchKeyEvent()` + 按键映射
  - `MainActivity.kt:389-435` — `handleNumericKey()`, `keyCodeToDigit()`, `showChannelNumberOverlay()`
  - `MainActivity.kt:411-425` — `keyCodeToDigit()` 映射表

  **Acceptance Criteria**:
  - [ ] `app/src/main/java/com/tivimatelite/input/InputHandler.kt` 存在
  - [ ] `app/src/test/java/com/tivimatelite/input/InputHandlerTest.kt` 存在
  - [ ] `MainActivity.kt` 中不再包含 `handleNumericKey`、`keyCodeToDigit`、`numericInputBuffer`
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: InputHandler 文件存在
    Tool: Bash (Test-Path)
    Steps:
      1. Test-Path app/src/main/java/com/tivimatelite/input/InputHandler.kt
    Expected Result: True
    Evidence: .omo/evidence/task-8-inputhandler-exists.txt
  ```

  **Evidence to Capture**:
  - [ ] task-8-inputhandler-exists.txt

  **Commit**: YES
  - Message: `refactor(input): extract InputHandler from MainActivity`
  - Files: `app/src/main/java/com/tivimatelite/input/InputHandler.kt`, `MainActivity.kt`, tests
  - Pre-commit: `gradle testDebugUnitTest --no-daemon && gradle assembleDebug --no-daemon`

- [ ] 9. 提取 ChannelLoader（频道加载 + 分组 + 恢复）+ 测试

  **What to do**:
  - 创建 `app/src/main/java/com/tivimatelite/loader/ChannelLoader.kt`
  - **TDD**：先写测试覆盖以下行为，再提取类
  - 从 `MainActivity.kt` 提取到 `ChannelLoader`：
    - `loadChannels()`, `loadChannelRows()`, `groupChannels()`
    - `restoreLastPlayedChannel()`, `reloadChannelsKeepingCurrent()`
    - `ChannelGroup` data class（变为顶级类或内部类）
    - `activePlaylistSource` 状态管理
  - `ChannelLoader` 通过 Context + scope 初始化，提供回调通知 MainActivity

  **Must NOT do**:
  - 不要改变频道分组算法（`LinkedHashMap` + `LinkedHashSet` 去重）
  - 不要改变播放列表加载优先级（remote → admin → local asset）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 涉及协程 + Context + 多种加载策略
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 8, 10, 11)
  - **Blocks**: Task 12
  - **Blocked By**: Tasks 5, 6, 7

  **References**:
  - `MainActivity.kt:226-363` — `loadChannels()`, `loadChannelRows()`, `groupChannels()`, `restoreLastPlayedChannel()`, `reloadChannelsKeepingCurrent()`
  - `data/RemotePlaylistRepository.kt` — 远程播放列表依赖
  - `player/PlaybackHistoryStore.kt` — 上次播放恢复依赖

  **Acceptance Criteria**:
  - [ ] `app/src/main/java/com/tivimatelite/loader/ChannelLoader.kt` 存在
  - [ ] `MainActivity.kt` 中不再包含 `loadChannels`、`loadChannelRows`、`groupChannels`
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: ChannelLoader 文件存在
    Tool: Bash (Test-Path)
    Steps:
      1. Test-Path app/src/main/java/com/tivimatelite/loader/ChannelLoader.kt
    Expected Result: True
    Evidence: .omo/evidence/task-9-loader-exists.txt
  ```

  **Evidence to Capture**:
  - [ ] task-9-loader-exists.txt

  **Commit**: YES
  - Message: `refactor(loader): extract ChannelLoader from MainActivity`
  - Files: `app/src/main/java/com/tivimatelite/loader/ChannelLoader.kt`, `MainActivity.kt`, tests
  - Pre-commit: `gradle testDebugUnitTest --no-daemon && gradle assembleDebug --no-daemon`

- [ ] 10. 提取 ChannelSwitcher（切台 + 故障转移 + HLS 重试）+ 测试

  **What to do**:
  - 创建 `app/src/main/java/com/tivimatelite/switcher/ChannelSwitcher.kt`
  - **TDD**：先写测试覆盖以下行为，再提取类
  - 从 `MainActivity.kt` 提取到 `ChannelSwitcher`：
    - `requestSwitchByDelta()`, `switchChannelImmediately()`
    - `playCurrentSource()`, `playNextSourceForCurrentChannel()`
    - `retryCurrentSingleSource()`, `tryForceHlsForCurrentSource()`
    - `scheduleBufferingFailover()`, `cancelBufferingFailover()`
    - `isUnrecognizedInputFormat()` 异常链遍历
    - 相关常量：`CHANNEL_ZAP_DEBOUNCE_MS`, `BUFFERING_FAILOVER_MS`, `SINGLE_SOURCE_RETRY_*`
  - `ChannelSwitcher` 通过构造函数接收 PlayerManager + 频道数据源引用
  - MainActivity 中保留 `playCurrentSource` 的调用入口但委托给 ChannelSwitcher

  **Must NOT do**:
  - 不要改变切台 debounce 语义（300ms 防抖）
  - 不要改变故障转移顺序（当前源 → 下一源 → 所有源失败后重试）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 最复杂的提取，涉及状态机 + 定时器 + 异常处理
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 8, 9, 11)
  - **Blocks**: Task 12
  - **Blocked By**: Tasks 5, 6, 7

  **References**:
  - `MainActivity.kt:365-550` — 所有切台 + 故障转移 + 重试逻辑
  - `MainActivity.kt:622-643` — `tryForceHlsForCurrentSource()` + `isUnrecognizedInputFormat()`
  - `MainActivity.kt:552-566` — 缓冲超时 failover
  - `PlayerManager.kt` — 播放器管理依赖

  **Acceptance Criteria**:
  - [ ] `app/src/main/java/com/tivimatelite/switcher/ChannelSwitcher.kt` 存在
  - [ ] `MainActivity.kt` 中不再包含 `requestSwitchByDelta`、`playNextSourceForCurrentChannel` 等
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: ChannelSwitcher 文件存在
    Tool: Bash (Test-Path)
    Steps:
      1. Test-Path app/src/main/java/com/tivimatelite/switcher/ChannelSwitcher.kt
    Expected Result: True
    Evidence: .omo/evidence/task-10-switcher-exists.txt
  ```

  **Evidence to Capture**:
  - [ ] task-10-switcher-exists.txt

  **Commit**: YES
  - Message: `refactor(switcher): extract ChannelSwitcher from MainActivity`
  - Files: `app/src/main/java/com/tivimatelite/switcher/ChannelSwitcher.kt`, `MainActivity.kt`, tests
  - Pre-commit: `gradle testDebugUnitTest --no-daemon && gradle assembleDebug --no-daemon`

- [ ] 11. 提取 ReadyStallWatch（缓冲监控）+ 测试

  **What to do**:
  - 创建 `app/src/main/java/com/tivimatelite/monitor/ReadyStallWatch.kt`
  - **TDD**：先写测试覆盖以下行为，再提取类
  - 从 `MainActivity.kt` 提取到 `ReadyStallWatch`：
    - `startReadyStallWatch()`, `cancelReadyStallWatch()`
    - `startNetworkSpeedMonitor()`, `formatSpeed()`
    - `startHeartbeat()`
    - `startPlaylistWatcher()`
    - 相关常量：`READY_STALL_*`, `NET_SPEED_UPDATE_MS`, `HEARTBEAT_INTERVAL_MS`
  - `ReadyStallWatch` 通过构造函数接收监听器（stall 检测到时回调）、Player 引用、binding 视图引用

  **Must NOT do**:
  - 不要改变 stall 检测阈值（300s 超时、60s 预热、300s 冷却恢复）
  - 不要改变心跳间隔（10s）

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 涉及协程定时器 + 循环监控逻辑
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 8, 9, 10)
  - **Blocks**: Task 12
  - **Blocked By**: Tasks 5, 6, 7

  **References**:
  - `MainActivity.kt:568-621` — `startReadyStallWatch()`, `cancelReadyStallWatch()`
  - `MainActivity.kt:437-463` — `startNetworkSpeedMonitor()`, `formatSpeed()`
  - `MainActivity.kt:694-702` — `startHeartbeat()`
  - `MainActivity.kt:245-257` — `startPlaylistWatcher()`
  - `MainActivity.kt:704-722` — 相关常量

  **Acceptance Criteria**:
  - [ ] `app/src/main/java/com/tivimatelite/monitor/ReadyStallWatch.kt` 存在
  - [ ] `MainActivity.kt` 中不再包含 `startReadyStallWatch`、`startNetworkSpeedMonitor`、`startHeartbeat`
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: ReadyStallWatch 文件存在
    Tool: Bash (Test-Path)
    Steps:
      1. Test-Path app/src/main/java/com/tivimatelite/monitor/ReadyStallWatch.kt
    Expected Result: True
    Evidence: .omo/evidence/task-11-stallwatch-exists.txt
  ```

  **Evidence to Capture**:
  - [ ] task-11-stallwatch-exists.txt

  **Commit**: YES
  - Message: `refactor(monitor): extract ReadyStallWatch, NetworkSpeedMonitor, Heartbeat from MainActivity`
  - Files: `app/src/main/java/com/tivimatelite/monitor/ReadyStallWatch.kt`, `MainActivity.kt`, tests
  - Pre-commit: `gradle testDebugUnitTest --no-daemon && gradle assembleDebug --no-daemon`

- [ ] 12. MainActivity 整合为协调器 (~300 行)

  **What to do**:
  - 将 `MainActivity.kt` 从 634 行缩减至约 300 行
  - MainActivity 变为"协调器"角色：
    - 在 `onCreate()` 中初始化 4 个提取类
    - `dispatchKeyEvent()` 委托给 `inputHandler`
    - 保留 `Player.Listener` 在 MainActivity 中（因为它需要访问多个提取类）
    - `onDestroy()` 集中清理所有提取类的协程
  - 删除所有已提取的私有方法和状态变量
  - 确保 `ChannelGroup` data class 迁移到合适的提取类或 model 包中

  **Must NOT do**:
  - 不要改变 `Player.Listener` 的回调行为
  - 不要更改 `onCreate()` 的初始化顺序
  - 不要更改 `onDestroy()` 的资源释放逻辑

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: 最关键的整合步骤，需要确保所有提取类正确协作
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: NO（依赖 Task 8-11 全部完成）
  - **Parallel Group**: Wave 4 (solo)
  - **Blocks**: F1-F4
  - **Blocked By**: Tasks 8, 9, 10, 11

  **References**:
  - `MainActivity.kt:33-634` — 完整文件（重构前全貌）
  - Tasks 8-11 的所有提取类

  **Acceptance Criteria**:
  - [ ] `(Get-Content app/src/main/java/com/tivimatelite/MainActivity.kt | Measure-Object -Line).Lines` → < 350
  - [ ] MainActivity 中 `onCreate()` 调用 4 个提取类的初始化
  - [ ] `dispatchKeyEvent()` 委托给 `InputHandler`
  - [ ] `Player.Listener` 仍在 MainActivity 中
  - [ ] `gradle assembleDebug --no-daemon` → BUILD SUCCESSFUL
  - [ ] `gradle testDebugUnitTest --no-daemon` → ALL PASS

  **QA Scenarios**:
  ```
  Scenario: MainActivity 行数检查
    Tool: Bash (Measure-Object)
    Steps:
      1. (Get-Content app/src/main/java/com/tivimatelite/MainActivity.kt | Measure-Object -Line).Lines
    Expected Result: < 350
    Evidence: .omo/evidence/task-12-mainactivity-lines.txt

  Scenario: 构建通过
    Tool: Bash
    Steps:
      1. gradle assembleDebug --no-daemon
    Expected Result: BUILD SUCCESSFUL
    Evidence: .omo/evidence/task-12-build.txt
  ```

  **Evidence to Capture**:
  - [ ] task-12-mainactivity-lines.txt
  - [ ] task-12-build.txt

  **Commit**: YES
  - Message: `refactor(main): reduce MainActivity from 634 to ~300 lines via extracted classes`
  - Files: `MainActivity.kt`
  - Pre-commit: `gradle testDebugUnitTest --no-daemon && gradle assembleDebug --no-daemon`

## Final Verification Wave

> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.

- [ ] F1. Plan Compliance Audit
  Agent: `oracle`. Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search codebase for forbidden patterns (Compose, Hilt, new Activity, etc.) — reject with file:line if found. Check: Room lines gone, MainActivity < 350 lines, HttpFetcher + 4 extracted classes exist, test files exist. Compare deliverables against plan.
  Output: `Must Have [4/4] | Must NOT Have [N/N] | Tasks [12/12] | VERDICT: APPROVE/REJECT`

- [ ] F2. Build + Test CI Gate
  Agent: `unspecified-high`. Run `gradle testDebugUnitTest --no-daemon --stacktrace --info` + `gradle assembleDebug --no-daemon --stacktrace --info`. Both must pass. Check CI config has test step.
  Output: `Build [PASS/FAIL] | Tests [N pass/N fail] | CI [PASS/FAIL] | VERDICT`

- [ ] F3. Real Manual QA
  Agent: `unspecified-high`. Verify evidence files exist in `.omo/evidence/`. Re-run acceptance criteria for Tasks 1-12. Spot-check full test suite: ≥6 M3U8Parser tests, ≥1 each for HttpFetcher/InputHandler/ChannelLoader/ChannelSwitcher/ReadyStallWatch.
  Output: `Tasks [12/12] | Evidence [N files] | AllTests [PASS] | VERDICT`

- [ ] F4. Scope Fidelity Check
  Agent: `deep`. For each task: read "What to do", read actual diff. Verify 1:1 — everything in spec was built, nothing beyond. Check "Must NOT do" compliance. Detect cross-task contamination.
  Output: `Tasks [12/12 compliant] | Contamination [CLEAN] | Unaccounted [CLEAN] | VERDICT`

---

## Commit Strategy

| Task | Type | Message | Pre-commit |
|---|---|---|---|
| 1 | build(test) | add JUnit4 + coroutines-test infra and CI test step | `gradle testDebugUnitTest --no-daemon` |
| 2+3 | chore(deps) | remove dead room-runtime/room-ktx dependency | (grouped with 2) |
| 4 | fix(web) | add AppLogStore.w to PlaylistStore.loadFromUrl failure path | `gradle assembleDebug --no-daemon` |
| 5 | test(parser) | add behavior-locking tests for M3U8Parser | `gradle testDebugUnitTest --no-daemon` |
| 6 | refactor(util) | extract common HttpURLConnection setup to HttpFetcher | `gradle test && gradle assemble` |
| 7 | fix(ui) | add Glide .error() fallback for channel logo loading | `gradle testDebugUnitTest --no-daemon` |
| 8 | refactor(input) | extract InputHandler from MainActivity | `gradle test && gradle assemble` |
| 9 | refactor(loader) | extract ChannelLoader from MainActivity | `gradle test && gradle assemble` |
| 10 | refactor(switcher) | extract ChannelSwitcher from MainActivity | `gradle test && gradle assemble` |
| 11 | refactor(monitor) | extract ReadyStallWatch from MainActivity | `gradle test && gradle assemble` |
| 12 | refactor(main) | reduce MainActivity from 634 to ~300 lines | `gradle test && gradle assemble` |

---

## Success Criteria

### Verification Commands
```bash
gradle testDebugUnitTest --no-daemon --stacktrace --info
# Expected: ALL PASS, ≥10 tests, 0 failures

gradle assembleDebug --no-daemon --stacktrace --info
# Expected: BUILD SUCCESSFUL
```

### Final Checklist
- [ ] Room 依赖已从 `app/build.gradle.kts` 删除
- [ ] `app/src/test/` 测试目录已创建，测试全部通过
- [ ] `HttpFetcher.kt` 已提取，两个调用者已迁移
- [ ] `InputHandler.kt`, `ChannelLoader.kt`, `ChannelSwitcher.kt`, `ReadyStallWatch.kt` 已提取
- [ ] `MainActivity.kt` < 350 行
- [ ] `ic_channel_fallback.xml` 存在，Glide `.error()` 已添加
- [ ] `.github/workflows/android.yml` 包含 `testDebugUnitTest` 步骤
- [ ] 无 MVP/MVVM/DI/Hilt/Koin 引入
- [ ] PlayerManager 仍为全局单例
