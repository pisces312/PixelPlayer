# Media3 1.10.1 → 1.11.0 升级计划（防回归）

> 状态：已实现 §3.1 最小修复并真机验证通过
> 日期：2026-09-06
> 相关分支：`1.11.0`（a2e5244c，含升级 + 删除保护 + gitignore + 解码器改动）、`master`（1.10.1 基线）

## 0. 核心结论（先说结果）

1. **暂停失效、mini player 状态异常是 Media3 1.11.0 引入的运行时回归**，根因已通过源码 diff 实锤：
   `MediaSession.Callback.onConnect` 的默认实现行为变更，导致 PixelPlayer 在 `onConnect` 里
   `super.onConnect()` 拿到的 `availablePlayerCommands` / `availableSessionCommands` 变成 **空命令集**。
2. **修复是一行代码级别的**：把 `super.onConnect(session, controller)` 换成
   `MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()`（详见 §3）。
3. 编译层面无阻塞：1.11.0 分支已能通过 R8 编译；「移除的符号」清单里没有任何一项被本项目使用。
4. 另有 4 处行为变更需在升级后回归验证（§4），均非阻塞、但需要实测确认。

---

## 1. 背景与目标

- 目标分支 `1.11.0` 已把 media3 `media3Session` / `media3Transformer` 从 1.10.1 升到 1.11.0（commit be37dc36，只改版本号无适配）。
- A/B 实测（同设备 BKQ-AN80）已确认：1.11.0 暂停失效、mini player 不显示；1.10.1 全部正常。启动自动播放两版本一致（是既有快照恢复设计，非回归）。
- 本计划的目标：锁定根因、给出最小修复、盘点所有潜在回归点、给出可执行的验证清单。

---

## 2. 根因定位（源码级实锤）

### 2.1 触发链路

`MusicService.kt:559-628` 覆盖了 `MediaLibrarySession.Callback.onConnect`，核心三行：

```kotlin
val defaultResult = super.onConnect(session, controller)   // ① 依赖父类默认实现
...
val sessionCommandsBuilder = SessionCommands.Builder()
    .addSessionCommands(defaultResult.availableSessionCommands.commands)  // ② 继承 session 命令
...
return MediaSession.ConnectionResult.accept(
    sessionCommandsBuilder.build(),
    defaultResult.availablePlayerCommands                  // ③ 继承 player 命令
)
```

### 2.2 默认 `onConnect` 的返回值在两个版本的差异

**1.10.1**（`MediaSession.java`）：

```java
default ConnectionResult onConnect(MediaSession session, ControllerInfo controller) {
  return new ConnectionResult.AcceptedResultBuilder(session).build();
}
```

`AcceptedResultBuilder(session)` 默认带 **`DEFAULT_PLAYER_COMMANDS`（全量 player 命令）** 与
`DEFAULT_SESSION_AND_LIBRARY_COMMANDS`。因此 `super.onConnect()` 返回完整命令集。

**1.11.0**（`MediaSession.java`）：

```java
default ConnectionResult onConnect(MediaSession session, ControllerInfo controller) {
  return session.getDeprecatedDefaultConnectionResult();   // ← 返回「未实现」标记结果
}

private ConnectionResult getDeprecatedDefaultConnectionResult() {
  return new ConnectionResult.AcceptedResultBuilder()
      .setAvailableSessionCommands(SessionCommands.EMPTY)   // ← 空
      .setAvailablePlayerCommands(Commands.EMPTY)           // ← 空
      .setSessionExtras(notImplementedBundle)               // ← BUNDLE_KEY_NOT_IMPLEMENTED=true
      .build();
}
```

### 2.3 为什么没有触发框架的「回退」保护

1.11.0 的 `MediaSessionImpl.onConnectOnHandler` 里有一套兼容逻辑：如果 `onConnect` 返回的结果带着
`BUNDLE_KEY_NOT_IMPLEMENTED` 标记，就回退去调 `onConnectAsync` 的默认实现（trusted → 全量命令）。

**但 PixelPlayer 的 `onConnect` 破坏了该标记**：它拿到 `super.onConnect()` 的带标记结果后，
用 `ConnectionResult.accept(sessionCommands, playerCommands)` 构造了一个**全新的、不带 sessionExtras 标记**的结果。
于是框架判定「这是应用自己实现的结果」，直接采纳 —— 结果就是 **空 player 命令 + 空 session 命令**。

### 2.4 对现象的解释

| 现象 | 解释 |
|---|---|
| 点暂停键无效 | 控制器 `availablePlayerCommands` 为空，`controller.pause()` 变成 no-op |
| mini player 不显示 | 控制器拿不到完整可用命令，UI 侧状态同步链路异常 |
| media key 暂停无效 | 默认 session 命令被清空，媒体按键事件无法正常派发 |

> 附：`MediaControllerSyncStateHolder` / `PlaybackDispatchStateHolder` 等 ViewModel 手里的
> `MediaController` 属于本进程、本应 `isTrusted=true`，但在 1.11.0 下同样被空命令集影响 ——
> 因为空命令集来自 `super.onConnect()` 的返回值，与 trusted 与否无关。

---

## 3. 修复方案

### 3.1 最小修复（推荐，1 行）

`MusicService.kt:582`，把：

```kotlin
val defaultResult = super.onConnect(session, controller)
```

改为：

```kotlin
val defaultResult = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
```

`AcceptedResultBuilder(MediaSession, ControllerInfo)` 是 1.11.0 新增的构造，按 `controller.isTrusted()`
返回默认命令集：trusted → `DEFAULT_PLAYER_COMMANDS` + `DEFAULT_SESSION_AND_LIBRARY_COMMANDS`；
untrusted → 只读命令集。这与 1.10.1 的 `super.onConnect()` 语义等价，且不再依赖已废弃的
`onConnect` 默认实现、不带「未实现」标记，框架会直接采纳结果。

- 改动面：1 行。
- 兼容性：`AcceptedResultBuilder(session, controller)` 在 1.10.1 不存在，**本修复只在 1.11.0 分支生效**，
  不能 cherry-pick 回 master（master 保持 1.10.1 无需此修复）。若未来 master 也升 1.11.0，合并时自然带上。

### 3.3 真机验证结果（2026-09-06，BKQ-AN80，minifiedDebug）

已应用 §3.1 修复并构建装机实测，证据链完整：

| 时间 | 事件 | 结论 |
|---|---|---|
| 启动 | `onConnect from package=...debug trusted=true` ×3，`actions=7340029`（完整命令位掩码，含 play/pause/seek/自定义命令） | 命令集恢复 ✅ |
| 发 PLAY | `Audio playback is changed ... state:started` → `state=PLAYING(3)` | 播放正常 ✅ |
| 发 PAUSE | `Audio playback is changed ... state:paused` → `state=PAUSED(2)` | 暂停生效 ✅ |
| PAUSE 后 8s | 无任何重新 PLAYING 记录，`speed=0.0` 保持 | **不再 84ms 自动恢复** ✅ |

> 对照：修复前 1.11.0 空命令集时，暂停后 AudioTrack `stopped` → 84ms 后 `started`（自动恢复），
> 或 media key 完全无响应。本次修复后暂停稳定保持。

### 3.2 备选方案（更彻底，改动面较大）

把 `onConnect` 迁移到 1.11.0 推荐的 `onConnectAsync`，返回 `Futures.immediateFuture(result)`。
优点是与新 API 对齐（`onConnect` 已标记 deprecated、是将来移除候选）；缺点是当前 `onConnect`
里的同步逻辑（reject wear、grantArtworkUriPermissions、性能埋点）都要搬进异步回调，改动约 20 行，
且需要在 1.10.1 上做兼容分支（`onConnectAsync` 在 1.10.1 不存在）。

**结论：先采用 3.1 最小修复；`onConnectAsync` 迁移作为后续技术债单独排期。**

---

## 4. 其它行为变更盘点（逐条评估影响）

来源：Media3 1.11.0 release notes（`github.com/androidx/media/blob/main/RELEASENOTES.md`）。

### 4.1 需要回归验证的（运行时行为，非编译错误）

| 变更 | 对 PixelPlayer 的潜在影响 | 风险 |
|---|---|---|
| **Session：`MediaSession` 线程约束收紧** — void 方法自动 post 到 app looper；getter 在非 looper 线程调用直接抛 `IllegalStateException` | 项目 `serviceScope = CoroutineScope(Dispatchers.Main)`，`session.player` 访问均在 Main；`appScope.launch(Dispatchers.IO)`（:1225/:1330/:1372）需确认块内不碰 MediaSession getter | 低（A/B 未见崩溃，但需核 :1225/:1330/:1372） |
| **Audio：修复多个 audio renderer 时事件 misroute** | DualPlayerEngine 是双 player（各自一个 renderer），非单 player 多 renderer；但依赖 `AudioRendererEventListener` 的码率/解码器事件，需确认事件仍正确归属 | 中（需实测事件归属） |
| **Audio：`DefaultAudioTrackBufferSizeProvider` 默认改固定 500ms buffer** | 可能影响音频起播延迟/缓冲感知 | 低 |
| **Audio：`MediaCodecAudioRenderer` 从平台解码器提取空间 channelMask** | 影响 DSD/多声道 downmix 路径（`SurroundDownmixProcessor`），需确认输出声道不变 | 中（多声道曲目实测） |
| **Track selection：无法识别的 codec profile 标记为 `NO_EXCEEDS_CAPABILITIES`** | 可能改变小众格式的轨道选择，配合硬件优先排序逻辑 | 低 |
| **Audio：移除 Google Pixel 的 EAC3-JOC→EAC3 回退** | 本机 qcom 不受影响；跨设备（Pixel）EAC3 需注意 | 低 |
| **ExoPlayer：动态调度默认开启** | 播放循环从固定间隔改动态，CPU 行为变化 | 低 |

### 4.2 已确认无影响的（编译/符号层面）

| 变更 | 结论 |
|---|---|
| 移除 `androidx.media3.exoplayer.MetadataRetriever` | ✅ 项目用的是 `android.media.MediaMetadataRetriever`（框架类），未受影响 |
| 移除 `MediaExtractorCompat` / `MotionPhotoMetadata` / `DummyTrackOutput` / `DummyExtractorOutput` / `C.generateAudioSessionIdV21` | ✅ 0 处使用 |
| `AudioSink.configure` 参数改为 data class | ✅ 项目只 override `DefaultRenderersFactory.buildAudioSink()`，未 override `ForwardingAudioSink.configure` |
| `DecoderAudioRenderer.getChannelMapping` 返回 `ImmutableIntArray` | ✅ 0 处使用 |
| 移除 `Mp4Extractor.FLAG_READ_MOTION_PHOTO_METADATA` | ✅ 0 处使用 |

---

## 5. 验证清单（升级后逐项实测）

1. **编译**：`:app:assembleMinifiedDebug`（R8）通过。
2. **暂停/播放**：UI 点暂停 → AudioTrack stopped 且**保持**暂停（不再 84ms 自动恢复）；点播放恢复。
3. **media key**：`adb shell input keyevent 127` 暂停、`126` 播放，均生效。
4. **mini player**：启动后底部播放条正常显示，标题/艺人/按钮态同步。
5. **外部控制器**：Android Auto / Wear / 蓝牙 AVRCP 的连接与命令不回归（用 `onConnect` 日志确认 trusted/命令集）。
6. **多声道/DSD**：播一首 5.1/DSD 曲目，确认 `SurroundDownmixProcessor` 输出与解码器事件归属正常。
7. **启动自动播放**：确认仍为既有快照恢复行为（预期：自动播；若要改成启动不播，另立需求）。

---

## 6. 分阶段执行建议

- **阶段一（立即）**：在 `1.11.0` 分支应用 §3.1 最小修复 → 构建 minifiedDebug → 装机走 §5 的 2/3/4 三项。
- **阶段二（验证后）**：若三项通过，再补 §5 的 5/6（外部控制器 + 多声道），确认无次级回归。
- **阶段三（技术债）**：评估 `onConnect` → `onConnectAsync` 迁移；评估 `:1225/:1330/:1372` 的 IO 线程块是否触碰 MediaSession getter。

---

## 附：关键源码位置

- 项目触发点：`app/src/main/java/com/theveloper/pixelplay/data/service/MusicService.kt:559-628`
- Media3 1.11.0 变更：`MediaSession.java` 的 `getDeprecatedDefaultConnectionResult()` / 默认 `onConnect` /
  `AcceptedResultBuilder(MediaSession, ControllerInfo)`；`MediaSessionImpl.onConnectOnHandler` 的「未实现」回退逻辑。
- 源码包：`dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.11.0/media3-session-1.11.0-sources.jar`
