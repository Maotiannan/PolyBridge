# X Fold5 双向实时翻译 App｜产品需求文档 PRD v1.0

> 状态：可作为 Codex 实施输入；通话注入、双蓝牙并发等高风险功能处于研究验证阶段，不代表已验证兼容。  
> 日期：2026-11-03  
> 目标设备：vivo X Fold5 国行，OriginOS 6（实际 Android API 级别与完整 Build Fingerprint 通过真机读取确认）  
> 使用场景：个人自用、非商业分发、单台 X Fold5 作为计算和控制主机、云端翻译优先；允许研究 Root / Magisk / Zygisk / LSPosed；默认中文↔法语，后续可扩展多语种。  
> 产品代号：**PolyBridge**（暂定，可重命名）

---

## 0. Codex 强制执行协议（高优先级）

1. **先研究再实现，但不要陷入无限调研。** 首轮必须交付可编译可安装的 Android 工程、P0 Audio Probe 诊断 APK、真实/模拟环境分离的测试与报告模板。用户尚未提供真机连接时，完成编译、单测、接口 Mock 和 P0 工具，不得虚报“已通过 X Fold5 验证”。
2. **主工程永远支持无 Root 运行。** `privileged-bridge` 与主工程物理、进程和功能开关隔离；无 Root/禁用模块时，面对面两模式与普通翻译键盘仍完整可用。
3. **不假设 Bootloader 可以解锁、Magisk 可以安装、Shizuku 能取得任意音频权限、双蓝牙麦克风可同时采集。** 全部走能力发现、真实测试、显式 `SUPPORTED / UNSUPPORTED / UNVERIFIED / DEGRADED` 状态机。
4. **不要把 Root/ADB 的“可能”描述为“已实现”。** 任何跨 App 音频采集或注入必须提供端到端录音样本、日志、实际远端听音确认，以及可复现的成功率。必须区分：系统电话与微信/WhatsApp VoIP；下行和上行；语音通话与语音消息。
5. **不得在主力手机上自动执行解锁、刷机、Exploit、关闭 Verified Boot 或修改系统分区。** 这些是用户手动批准、独立备份与风险接受之后的独立研究步骤；本工程不得自动执行。
6. **所有通话录音/翻译必须由用户显式启动，并提醒取得对方知情同意。** 禁止隐蔽监听、后台静默开启、跨会话偷偷录音；Root Hook 仅在用户授权的目标进程和开启的会话中工作。
7. **每一阶段交付 runnable code + 自动测试 + CHANGELOG + 测试矩阵 + 硬性验收结果。** 不允许仅产出规划稿。实现有真实约束时，记录具体阻碍与降级，继续完成其他模块。
8. **严格可插拔。** 翻译服务商、音频设备、通话采集、麦克风注入、音频存储、诊断必须各自隔离；禁止供应商字段泄漏进 UI/domain。
9. **不要假定云模型支持自定义词库或双向单会话。** 若模型仅支持固定目标语言，使用两个方向独立会话；精准翻译采用单独 ASR → 翻译 → TTS 管线。
10. **开始执行前检查现有仓库。** 若仓库为空则初始化；若已有项目则遵守已有代码与文档，不要覆盖用户未提交变更；先输出 `IMPLEMENTATION_PLAN.md`，随后直接实施 P0/P1 的可完成部分。

## 1. 产品目标、成功定义与限制

### 1.1 产品目标

一台 X Fold5 实现：
- **F1 面对面自由监听**：预设语言 A/B；双方轮流或打断说话，自动识别语言与转录，生成对方语言的语音并播放；记录中/法双语；切换手机/蓝牙麦克风；尽可能减少播报造成的回声。
- **F2 面对面双屏聊天**：展开后上下各一半，朝向对坐双方；每侧各自按住说话、松开发送，生成本国语言原文及对方语言译文，消息式展示、重播、修改与继续。
- **F3 微信/WhatsApp 实时语音通话辅助**：在单台 X Fold5 上，优先尝试获取远端法语语音 → 中文译文和耳机播报，允许保留极低音量远端原声；进一步尝试获取本地语音→法语合成语音→注入对方通话上行，让远端听到法语。两个方向独立验收。
- **F4 微信/WhatsApp 语音消息**：在被系统允许时录制正在播放的语音消息并展示双语字幕；否则提供经用户选择的文件共享/导入降级；用独立翻译输入法向聊天框输入目标语言文本；合成目标语言音频并分享，原生平台语音条仅作为实验功能。
- **F5 多副蓝牙耳机**：至少支持多设备管理、配对/连接识别、切换主动麦克风、耳机分配用户/目标语言，经过能力验证后支持多个耳机同步输出；多个独立耳机麦克风的真正并发采集属于设备能力有条件功能，必须设计实验与可选外设桥接。

### 1.2 成功定义

| 级别 | 交付成功标准 | 不得偷换概念 |
|---|---|---|
| MVP | F2 完整、F1 基础流式翻译完整、历史可查、单蓝牙收音稳定、可安装 APK | 使用 Mock 产生声音不算实际模型端到端成功 |
| V1 | F1 抢话/回声/断线恢复稳定、F4 输入法文字回复与可分享音频、双耳机能力探测与降级 | 同时连接两副耳机不等于两支麦克风同时采集 |
| V1.5 | F3 在真实微信及 WhatsApp 分别证明远端音频获取并正确耳机混音 | 通过系统电话录音不等于 VoIP 成功 |
| Research | F3 独立证明上行合成语音进入远端对方的耳机，且不会循环/原音泄漏 | 本机播放法语不等于远端听到法语 |

### 1.3 非目标

不提供云端代打电话、不依赖电脑持续作为实时媒体网关、不绕过端到端加密、不获取其他用户账户、不开启隐蔽录音、不破解第三方业务账户。单台手机为运行主机；允许蓝牙/USB-C 耳机或专用外接音频接收器作为外设，但使用外设需要用户明确选择。

## 2. 能力清单及约束：明确可做、待验证、降级

| 需求 | 无 Root | 特权/Root 路线 | 成功检测 | 失败降级 |
|---|---|---|---|---|
| F1 麦克风 + 流式语音翻译 | 可以 | 不必要 | Mic PCM / 双向字幕 / TTS | 按住说话 |
| F2 双屏按住说话 | 可以 | 不必要 | 发送、转录、翻译、播报 | 单屏纵向聊天 |
| F3 远端 VoIP 下行 | 常规 AudioPlaybackCapture 对通话受限 | Shell/Shizuku 路线先验；再系统/进程音频采集 | 微信、WhatsApp 远端分别有可识别 PCM，低误混 | 用户允许的扬声器+麦克风近场拾音；会受通话独占限制 |
| F3 本地 VoIP 麦克风 | 普通并发录音常静音 | 受控进程 Hook 上行录音点/系统镜像 | 本地 PCM 正常，原 VoIP 不断音 | 用户手动语音翻译键盘，不宣称实时通话 |
| F3 注入远端 VoIP 上行 | 不具备通用公开 API | 进程级虚拟麦克风；若不兼容则研究系统音频层 | 远端设备实际收到译音，独立于本机回放 | 提示用户直接讲原语、文字发译文 |
| F4 语音消息播放采集 | 媒体用途且源 App 允许才可以 | 特权补充 | 语音消息可独立转录，不混入 TTS | 音频导入/分享/文本粘贴 |
| F4 语音翻译输入法 | 可以 | 不必要 | 输入聊天框的译文可编辑 | 应用内复制 |
| F4 原生语音条替代 | 无通用 API | 虚拟麦克风注入或专用自动化（实验） | 远端收到/能播原生语音条 | 分享目标语言音频文件 |
| 多副耳机同时收听 | 视 LE Audio/OriginOS 支持 | 特权音频路由/专用 USB | 双耳同一时刻实际播放 | 单活动耳机，随时切换 |
| 多副耳机独立同时收音 | 无通用公开能力保证 | 受支持硬件/专用蓝牙多通道接收器 | 能分别记录每支麦克风的独立 PCM | 单一主动蓝牙麦克风 + 轮流接管 |
| 每副耳机只听各自语言 | 需独立输出流、路由与硬件支持 | 系统级音频策略/外设独立声道 | A仅听法语，B仅听中文 | 双方耳机听共用混音流，界面告知 |

**Important:** vivo 官方参数只公开蓝牙 5.4、相关音频编码；并未据此证实 X Fold5 上 Auracast/双 BLE Audio 输出及多麦克风输入能力。版本与耳机组合必须实测。Android 支持 LE Audio 多设备，不等于 vivo 的具体实现开放给第三方 App。

## 3. 用户场景与交互

### 3.1 全局界面

首页四入口：`自由监听`、`双屏面对面`、`通话辅助`、`语音消息/翻译键盘`。公共顶部：语言 A/B 选择、模型/网络状态、录音指示、会话名称。公共设置：语言与口音、音量与播放路由、自动播放、术语表、对话历史保留策略、隐私开关、设备配置、实验功能状态。

设备面板按每个设备显示：名称、蓝牙地址哈希、设备类型（A2DP/HFP/LE Audio/USB/手机）、支持输入/输出/并发播放状态、实际 routed device、延迟测量、分配对象、独立目标语言。不要依赖蓝牙“已连接”图标判定正在收音。

### 3.2 F1 自由监听

1. 设置语言（默认 zh-CN ↔ fr-FR）；从 `手机近场` / `单耳机` / `多耳机共享` / `实验独立双耳` 选择配置。
2. 点击“开始”，前台可见录音状态；`AudioRecord` 获取 PCM，VAD 建立语音片段与时间戳。
3. LID 判断源语言（对未知、极短句和混合语句引入置信度门槛）；路线选择 A→B 或 B→A；一旦不确定，先显示字幕，延迟播报，禁止快速在两种语言之间反复抖动。
4. S2S 流式输出译文和 PCM；音频由路由器选择手机扬声器、耳机或广播输出；字幕包括 `partial`、`committed` 两级。
5. 抢话：新说话事件可 duck / pause / cancel 尚未播放的译音；已提交文字保留。对音频回灌设 echo guard：比较已播报 TTS 与麦克风参考 + AEC + 语种/时间过滤，严禁无限循环。
6. 网络异常：显示明确状态；保存原文和未完成消息，恢复后不重复播放；断流超时转为单句离线/文字降级（离线模型未配置时只录原文，提示未翻译）。
7. 双方同时讲话无法保证可靠分离；有独立音频通道优先按流标记 speaker_id，单麦克风时提示“重叠发言，可能失准”。

**默认的输出策略**：手机扬声器播法语给对方、耳机播中文给我；若蓝牙 duplex 与分流不能同时实现，则动态回退为两种语言都用一个输出，并提示“当前无法分路播放”。不得通过 `setPreferredDevice()` 返回 true 就宣称路由成功，必须读取 `getRoutedDevice(s)` 并做听音测试。

### 3.3 F2 双屏面对面

折叠展开后上下各 50%，对面区域旋转 180°（可关闭），每侧独立：语言名、消息列表、按住录音、撤销、重播、文字纠正。对面区域触摸坐标/键盘适配旋转方向。折叠时退化为单屏双角色聊天，录音会话不因 `Activity` 重建而丢失。

状态流程：`IDLE → RECORDING → UPLOADING/TRANSCRIBING → TRANSLATING → SPEAKING → COMMITTED`；按住期间滑动取消；松手后立即显示待处理气泡；原声及译文可分别编辑纠错，编辑后 TTS 可重生成；发送者身份与语言方向由按键确定，不用 LID 猜测。

F2 默认分步管线：ASR → 文本翻译 → TTS（独立于实时引擎），有金额/日期/数量的术语及核验提醒。转录文本和翻译文本分别保存，不覆盖。

### 3.4 F3 通话辅助

前台显式启动，用户选微信/WhatsApp；显示权限/功能就绪状态（下行采集、上行读取、耳机混音、上行注入各自独立指示）。提供三个可逐级升级模式：

- **F3-A 仅本端翻译**：截取远端音频（经验证可行的受控途径）→ 原法语 / 中文字幕 → 中文翻译在耳机播放；原声优先保留 0~20% 可调；如果无法独立调整原声增益，标记“不可独立调整”，不能误将整台手机静音。
- **F3-B 本端翻译 + 译音手动上行**：经验证本地音频可读取；录一段中文生成法语，点击“发送译音”后注入可控 VoIP 麦克风上行；远端验证成功才开放。
- **F3-C 真正全双工**：下行与上行同时实时翻译，支持打断、状态跟踪、优先级队列、回声抑制。仅 F3-A 与 B 都有可靠真机证据才允许开发。

上下行独立音频流，不得把双方混音后送进同一流式翻译会话。支持目标 App 更新后自动熔断、立即“恢复原始麦克风”紧急按钮、系统通话优先、耳机断开自动停用注入。

**微信/WhatsApp 的一切成功声明要求：** 两台参与通话的终端实际听音（另一台只作为对端验证终端，不承担运行翻译引擎），往返录音证据、50 次重复通话尝试以及 app 版本/build 指纹。用户“翻译必须在一台 X Fold5 完成”不排斥为了验收用另一台电话做对端。

### 3.5 F4 语音消息与翻译输入法

**接收：** 提供悬浮窗开启/关闭；经用户授权 `MediaProjection` 尝试捕获微信/WhatsApp 语音条，如只见静音显示“源 App 不允许捕获，改用导入/分享”；支持文件选择器导入音频，直接跑 ASR→文本翻译，双语字幕和重播。不得暗中访问其他 App 沙箱语音文件。

**回复：** 制作独立 Android `InputMethodService`，用户手动切换到“翻译键盘”后按住说中文，识别并翻法语，显示候选 + 修改，再 `InputConnection.commitText()` 插入；不擅自点击发送。TTS 生成法语音频，经 `FileProvider` + `ACTION_SEND` 共享目标应用。若微信/WhatsApp 版本不接收音频则引导手动导出；原生语音条桥接仅在 F3 上行注入成功后复用验证。

### 3.6 F5 多副蓝牙耳机

分层定义，不能把“连接多副”当成“独立多路”：

- **Tier 0 多设备管理（必做）**：能显示两副及更多已配对耳机，指定主动耳机、主动麦克风、路由回显；连接状态变化时无崩溃。
- **Tier 1 多耳机共享听音（条件目标）**：检测 LE Audio Audio Sharing / Auracast / 厂商支持后让多副耳机同时听相同译音；允许音量分别调节仅在系统暴露控制时。若 X Fold5 不支持，呈现“设备不支持”，不得做虚假的模拟。
- **Tier 2 双用户独立译音（研究目标）**：若系统存在多个真实可编址输出通道，按 `listener_id → target_language → sink_device` 分发不同音频；如果 Auracast 单广播只发送同一音频，则不能作为双译音分路；可研究受控特权路由或专用 USB 多路无线音频接收器。
- **Tier 3 多副耳机各自麦克风同时工作（外设条件目标）**：优先验证 X Fold5 厂商路由能力；否则选择具备独立多声道、能向 Android 暴露多路输入的 USB-C 2.4GHz/蓝牙接收硬件。先做 class-compliant USB Audio 输入枚举及分声道验证，再选型外设。**不承诺普通两副 TWS 耳机直接接手机就能并行开麦。**

实际应允许 2～4 人。以“手机为主机、耳机为外设”的方式工作。后续可加具体外设适配器；禁止现在就写死某个未经验证的耳机型号。

## 4. 系统音频突破方案：严格按照收益/风险排序

### 4.1 Route R0：公开权限与系统功能（无 Root）

`AudioRecord` / `AudioTrack` / `AudioManager` / `MediaProjection + AudioPlaybackCapture` / `AudioDeviceCallback` / `getActiveRecordingConfigurations()` 等诊断。注意 Android 常规播放捕获仅包含 `USAGE_MEDIA / GAME / UNKNOWN` 且需播放方允许，`USAGE_VOICE_COMMUNICATION` 不能靠普通 `MediaProjection` 捕获。并发录音下无优先权 App 会得到静音而非必然抛错。

测试每个通话场景的实际 `AudioRecord.getActiveRecordingConfiguration()?.isClientSilenced`、PCM RMS、频谱、源/播放用途、蓝牙线路、可听性；不要仅看 API 调用成功。

### 4.2 Route R1：Shizuku / Wireless Debugging / shell 能力验证（低侵入特权）

研究 `ShizuCallRecorder`、`CallVault` 这类在 Android 上尝试更高权限录音的开源项目；先审计其源代码、Android 版本范围、对 VoIP 的成功条件和临时权限生命周期，再制作**只读/本地短片段**兼容探针。探索 shell 可访问的系统录音/回环接口，必须注明 `adb shell` 权限与 `CAPTURE_AUDIO_OUTPUT` 特权许可不同，不能依赖 `pm grant` 给予签名权限。部分 Android 设备/ROM 可能完全无效。

如果微信或 WhatsApp 的远端语音无法被完整、独立、稳定捕获，R1 判定失败，停止此路线，不反复增加悬浮窗/辅助权限假装能解决。

### 4.3 Route R2：Root + 目标进程 LSPosed/Zygisk Hook（主要高权限研究路径）

**最优先研究上行注入。** 候选项目 `XMicInject`、`PhantomMic`、`GlassMic`。研究其 Java `AudioRecord.read`、native AAudio、OpenSL ES 以及 Android framework/native 路径覆盖率；只在用户授权的微信/WhatsApp 包名作用域生效，严禁 system-wide 默认 Hook。模块从本地守护进程以 IPC（Unix domain socket 或其它受保护本地通道）读取目标译音 PCM，明确 16/24/48 kHz、mono/stereo、缓冲深度、丢帧与时间戳。

上行注入管线：`耳机MIC/可用上行镜像 → 翻译引擎 → PCM输出 → PcmRingBuffer/jitterbuffer → hook target VoIP AudioRecord → remote hears translated speech`。必须保留 `PASS_THROUGH / REPLACE_WITH_TRANSLATION / MIX_ORIGINAL / MUTE` 可切换，前台用户明确指示当前模式；`Watchdog` 超时、IPC断开、App崩溃时立即恢复真实麦克风。

**下行捕获单独研究。** 候选位点：目标 VoIP 进程 AudioTrack 播放点、native 输出调用、系统音频服务回环/混音点；先从实际 trace 判断微信和 WhatsApp 走哪种音频 API。不要假设 Hook `AudioTrack.write` 一定能抓到 WebRTC 等 native 路径。系统音频 HAL/AudioFlinger 路径升级敏感度高，仅 R2 不覆盖且 Root 稳定可用才开启 R3。

必须承认：LSPosed / Zygisk 模块可能随 ROM、微信/WhatsApp、ABI、音频后端升级失效；可能导致崩溃、通话中断、隐私风险。项目开源仓库的 README 不构成兼容证明。

### 4.4 Route R3：Root + AudioPolicy/AudioFlinger/HAL 研究（最后手段）

仅在设备已获得可恢复且持久的高权限环境、R2 失败并有充分调试能力时允许研究。目标是上/下行独立采集、系统级增益/音频策略及虚拟麦克风音频进入目标 App 的可行性。**不要在未验证的 X Fold5 上直接修改 vendor 音频策略、分区或驱动。** 先从 AOSP 对应版本、厂商实际配置、读日志与非侵入探针开始；设计独立可撤销模块，能恢复原始 audio policy。设备一旦出现重启、通话断开、支付安全异常等立即停用。

### 4.5 单手机不 Root 兜底

任何更高权限方案失败，仍保留 F1/F2/F4，并提供通话“手动翻译助手”：悬浮语音输入、文字翻译和复制/发送、文件分享。若 WhatsApp/微信扬声器通话状态下普通麦克风能合法录到外放音频，可作为低质临时方式，但 **蓝牙耳机通话下通常不具备这个兜底**，也不能宣称满足 F3 全自动要求。

## 5. 工程架构（必须模块化）

```
:app                         # 首页、导航、前台服务、权限/生命周期、Compose UI
:core:domain                 # 语言对/用户/会话/消息/音频源、统一状态机、异常
:core:audio                  # AudioRecord、AudioTrack、AudioManager、VAD、AEC、重采样
:core:device                 # Bluetooth/LEAudio/USB 路由、能力探测、设备分配
:core:translation            # S2S、ASR、TextTranslate、TTS 接口及协调器
:provider:qwen               # Qwen 模型适配器（配置化，不假设区域与能力）
:provider:gemini             # Gemini Live Translate 适配器
:provider:openai             # gpt-realtime-translate 适配器
:core:store                  # Room、DataStore、加密、缓存、会话恢复
:feature:listen              # F1
:feature:dualscreen          # F2
:feature:callassist          # F3 仅基于能力矩阵开放功能
:feature:voice-message       # F4 收音/音频文件/字幕
:feature:ime                 # F4 输入法，可独立 APK/模块
:feature:diagnostics         # Audio Probe / P0 测试 / 结果导出
:privileged:api              # 抽象契约、能力检查；主 APP 只依赖这个接口
:privileged:shizuku          # 条件构建/运行，未安装不影响普通版
:privileged:root-bridge      # 独立进程/安装物，只有用户同意才启用
:backend                     # 单独后端，私密密钥保管、凭证、限额、监控
```

**Android 技术栈**：Kotlin、Jetpack Compose、Coroutines/Flow、Room、DataStore、Hilt、OkHttp WebSocket/Ktor（按项目选择其一）、Jetpack WindowManager、Foreground Service、Media3/自有 PCM renderer；需要低时延时以 NDK/Oboe 承载处理，但不要一开始引入不必要的 JNI 复杂度。`minSdk` / `targetSdk` 以实际设备和当期编译环境确认；针对 Android 16 进行测试，不把未验证的 API 常量写死。

**基础端口/契约（建议）**：

```kotlin
interface AudioInputSource {
    val capability: StateFlow<AudioInputCapability>
    suspend fun start(config: CaptureConfig): Flow<PcmFrame>
    suspend fun stop()
}
interface AudioOutputSink {
    val routedDevices: StateFlow<List<AudioSinkDevice>>
    suspend fun play(stream: Flow<PcmFrame>, target: ListenerId): PlaybackId
    suspend fun duckOrCancel(id: PlaybackId, reason: InterruptReason)
}
interface SpeechToSpeechProvider {
    val capabilities: ProviderCapabilities
    suspend fun open(request: S2SSessionRequest): S2SSession
}
interface BatchTranslationPipeline {
    suspend fun recognize(audio: AudioClip, source: LanguageTag): Transcript
    suspend fun translate(text: String, source: LanguageTag, target: LanguageTag): Translation
    suspend fun synthesize(text: String, voice: VoiceConfig): Flow<PcmFrame>
}
interface PrivilegedAudioBridge {
    val status: StateFlow<PrivilegeCapabilityReport>
    suspend fun startDownlinkCapture(target: ExternalApp): Result<AudioInputSource>
    suspend fun startMicInjection(target: ExternalApp, mode: InjectionMode): Result<MicInjectionSession>
    suspend fun restoreOriginalAudio(): Result<Unit>
}
```

禁止 `Feature` 层直接调用平台 Root 命令或云供应商 SDK。所有设备级状态都要可观察、可持久诊断、支持用户强制停止。

### 5.1 数据结构 / 事件

重要实体：`Conversation(id, type, languages, startedAt, endedAt, privacyPolicy)`；`Participant(id, language, deviceBinding?)`；`Utterance(id, conversationId, speakerId, sourceLanguage, targetLanguage, startedAt, endedAt, sourceText, translatedText, status, provider, confidence?)`；`AudioSegment(id, utteranceId, kind, localUri, sampleRate, duration, retention)`；`DeviceRoute(deviceId, role, transport, actualInput, actualOutput, capability)`；`AudioProbeResult(buildFingerprint, appPackage, appVersion, route, expectedSignal, rms, silenceState, verdict, notes)`。

状态事件采用增量投影：`speech.started`, `transcript.partial`, `transcript.committed`, `translation.partial`, `translation.committed`, `audio.chunk`, `audio.completed`, `speaker.interrupted`, `route.changed`, `provider.disconnected`, `provider.reconnected`, `injection.restored`；每事件具有 `sessionId / utteranceId / sequence / timestamp`，应对乱序、重连和重复。

消息最终状态：`PENDING / CAPTURING / TRANSCRIBING / TRANSLATING / SPEAKING / COMMITTED / FAILED / CANCELLED`；不可把 partial 字幕直接覆盖真实已提交文本而不保留修订轨迹。避免 TTS 音频在后台重试时被重复播放。

### 5.2 模型策略

- **默认实时候选**：`qwen3.8-livetranslate-flash-realtime`、`gemini-3.5-live-translate-preview`、`gpt-realtime-translate`。在编译/连接前查询当前官方 API 文档和账号能否用，检查中法语种、音频格式、认证、WebSocket/WebRTC、产出的源文/译文与计费；任何服务不可用则 UI 禁用而非崩溃。
- 每个源 speaker × 目标语言独立翻译上下文；纯双向混合输入应做源语言方向识别，不把双方混为同一原始会话。
- F2/F4 优先可纠错的 ASR→Translate→TTS；可按用户选择同一供应商或混搭。术语库主要用于文本管线，不得声称所有 S2S 直译模型支持注入术语。
- API Key 只在服务端或由用户明确配置的本机安全凭据中，优先后端颁发短时凭证；必须保证生成的 APK 中没有固定服务端密钥。
- 采样统一定义 `PcmFrame(samples, sampleRateHz, channelCount, ptsMonotonicNs, sourceId)`，根据供应商需要重采样与编码，禁止把 16k PCM 硬当作 24k 播放。

## 6. 端到端质量、音频仲裁、延迟预算

- `AudioCaptureCoordinator` 每个录音源单独轨道；延迟采样约 20 ms/帧或按设备实际最佳回调配置；音频帧携单调时钟时间戳与原始设备 ID。
- `SpeechArbiter` 避免两个说话人或译音互相触发；每句有去抖时间、语种置信度、VAD 最短长度、最大分句长度；系统 TTS 输出时开启 echo guard，检测异常后提示。
- 翻译流要支持 `start/partial/final/cancel/retry`，打断可中止尚未提交的合成语音，同时原始 transcript 不丢。
- 耳机路由在 HFP 通话与 A2DP/LE 高保真之间可能切换；实际 sample rate、latency 要每设备测量，不承诺固定 48 kHz 高清收音。
- 默认仅在明显说话边界进行模型/路由切换，以免音频流中途错位。
- 建立 `latency` 五段：`mic_first_pcm`、`vad_speech_start`、`provider_first_text`、`provider_first_audio`、`actual_output_start`；统计 p50 / p95。

**非功能验收门槛（初始目标，真实测试可修订）**：F2 结束说话→译音首播 p50 ≤ 3s；F1 开始发言→译音首播 p50 ≤ 3s（长句/网络波动另计）；触发抢话→原 TTS duck/pause p95 ≤ 500ms；30 分钟单会话无不可恢复崩溃；网络中断后恢复不会重复播报已完成句；关键金额/日期必须以原文+译文成对呈现。不得因为追求低延迟将准确率风险掩盖。

## 7. P0 Audio Probe：第一份必须交付的可安装 APK

### 7.1 诊断界面

四页：`设备总览`、`麦克风探针`、`播放捕获探针`、`VoIP/蓝牙联调`；一键输出 JSON + 人类可读 Markdown 日志，不导出原始通话音频，除非用户单独选择并同意。开关：录音 10 秒、停止、播放测试音、检视设备、申请 MediaProjection、查看路由及静音原因、导出。

### 7.2 自动收集的非敏感信息

- `manufacturer, model, ro.build.fingerprint, ro.build.version.sdk, Android security patch, OriginOS version`（私有文件脱敏）。
- `AudioManager` 当前 MODE、可用输入/输出设备、通信设备、支持 AudioDevice Type、蓝牙 profiles/LE 支持表（仅实际可查询项）、实际 routed device。
- `AudioRecord` 实际采样率/通道、读取长度、输入源、isClientSilenced (若 API 可用)、RMS、peak、有效音频比、连续性与中断。
- `MediaProjection` 是否获授权、捕获配置和采集 10 秒 PCM 能量、用户目标 App、App版本号。
- 是否存在 Root、Shizuku、LSPosed、Zygisk 的检测结果（仅检测，禁止自动安装/启用）。

### 7.3 真机测试矩阵（至少）

| ID | 场景 | 输入 | 输出 | 期望证据 |
|---|---|---|---|---|
| P0-01 | App 前台独立录音 | 手机 MIC | 手机扬声器 | 正常 PCM、标记设备 |
| P0-02 | App 前台独立录音 | BT-A MIC | BT-A | 正常 PCM、带路由证据 |
| P0-03 | 收 BT-A + 手机播 | BT-A MIC | 手机扬声器 | 真实听音、路由/静音状态 |
| P0-04 | 同时连接 BT-A、BT-B | 尝试分别录音 | 尝试同/不同流播放 | 是否双输出、能否分别获取两路 PCM |
| P0-05 | 两副 BLE Audio 耳机（如具备） | 单活跃 MIC | 双耳输出 | LE Audio Share 能力实测 |
| P0-06 | 微信语音消息播放 | PlaybackCapture | BT/手机 | PCM/RMS + 识别成功率 |
| P0-07 | WhatsApp 语音消息播放 | PlaybackCapture | BT/手机 | 同上 |
| P0-08 | 微信 VoIP 呼入、呼出 | 普通MIC / PlaybackCapture | BT-A HFP | 本地/远端分别是否可得 |
| P0-09 | WhatsApp VoIP 呼入、呼出 | 同上 | BT-A HFP | 同上 |
| P0-10 | VoIP 通话中译音播放 | 无/当前 MIC | BT-A | 远端是否受干扰、是否路由成功 |
| P0-11 | 锁屏/折叠/后台/耳机断开 | 变化输入 | 变化输出 | 报告重建时间和数据丢失 |
| P0-12 | Shizuku/Shell 探针（经许可） | 受控远端样本 | 本机 | 是否有稳定分离的下行 PCM |
| P0-13 | 可用 Root/Hook 测试（经许可） | 目标 App MIC | 对端真机 | 对端听到合成音频且 Fail-Open 可靠 |

P0-12/13 为条件测试；没有权限时标记 `UNVERIFIED`, 禁止强行交付刷机脚本。多耳机测试至少包含“仅已连接”、“可同时播放同一音频”、“能否播放分离内容”、“能否取得两路独立 MIC”四种结果。

### 7.4 诊断示例 JSON

```json
{
  "caseId": "P0-09",
  "device": { "model": "vivo X Fold5", "originOs": "6", "sdk": null, "buildFingerprintHash": null },
  "targetApp": { "package": "com.whatsapp", "version": null, "callType": "voip-outgoing" },
  "route": { "inputDevice": "BT-A", "outputDevices": ["BT-A"], "bluetoothProfile": "UNKNOWN" },
  "probe": { "method": "AudioPlaybackCapture", "permissions": [], "recordStarted": false, "clientSilenced": null, "rmsDbfs": null, "durationMs": 10000 },
  "verdict": "UNVERIFIED",
  "evidence": [],
  "notes": "No real-device test has been executed"
}
```

报告必须附每种 Route 的能力判断和后续决策，不能把无测试数据当作成功。

## 8. 阶段、工作包与里程碑

| 阶段 | 必须交付 | 验收门槛 | 跳出条件 |
|---|---|---|---|
| S0 工程初始化 | Gradle 构建、模块、版本目录、CI、模拟模型、设备状态界面 | `assembleDebug`, unit tests 通过 | 立刻开始 S1 |
| S1 P0 音频诊断 | Probe APK、JSON/MD 日志、BT 路由 + 采集试验、真实设备表 | 模拟可执行；真机待用户连接，显式标记 | 形成采集/路由矩阵 |
| S2 F2 双屏翻译 MVP | 双屏、PTT、ASR→翻译→TTS、Room、历史、异常反馈 | 20轮中法对话无丢消息；语音成功 | 能用于实际面对面 |
| S3 F1 实时监听 | 流式 S2S、双语 LID、抢话、AEC、路由 | 实际含打断的15分钟测试 | 能稳定持续对话 |
| S4 F4 跨App | IME、双语字幕、可捕获语音条/文件导入、音频分享 | 输入框真实可插文本；语音条采集按 App 逐项记录 | 形成可用回复功能 |
| S5 多耳机 | Tier0 + 真实设备测试，Tier1/2/3 依据能力开启 | 双耳播放/多麦克风各自独立验收 | 明确原生/外设路线 |
| S6 通话高权限研究 | R1→R2→R3 逐级验证、独立桥接、紧急恢复 | 微信及 WhatsApp 双向分别出真实证据 | 成功才转产品化，否则保留降级 |
| S7 稳定与隐私 | 断线、OOM、后台、折叠、脱敏、回归、耗电 | 不崩溃/不泄漏凭据/历史可恢复 | 可长期个人使用 |

**任务依赖**：S0→(S1 并行 S2)→S3；S4 可与 S3 并行；S5 在 S1 后；S6 只从 S1 的证据结果启动。禁止等待 S6 才交付基础可用 App。

### 8.1 Codex 具体首轮任务（立即开工）

**本轮必须实际写代码和测试，不得止步于 PRD。**

1. 读取仓库/现有工程；输出 `/docs/IMPLEMENTATION_PLAN.md`，列出已具备前提、明确阻塞和下一步可执行任务。
2. 建立可编译、可安装的 Kotlin Compose Android 项目（若仓库已有工程则增量修改），写出 `README.md`、`docs/ARCHITECTURE.md`。
3. 做 `DiagnosticsActivity`/诊断 Compose 页面，展示型号、SDK、音频设备列表、当前路由、可手动录10秒测试音、PCM RMS、是否静音。
4. 做 `AudioInputSource`、`AudioOutputSink`、`DeviceCapabilities` 的第一版契约与基础 Android 实现，支持蓝牙设备接入/断开监听。
5. 增加 `MediaProjection` capture 可选测试和清晰权限/失败提示；不要假设可以采集 VoIP 音频。
6. 建立双屏对话 UI 雏形、可见的录音按钮、消息列表、中文/法语方向与本地 Mock 翻译。用户没有云密钥时也可完全运行调试。
7. 追加 unit tests 和 smoke tests；尽可能构建 APK。若 CI/SDK 环境缺失，则给出精确阻塞原因和可复现的本地构建命令。
8. 输出 `docs/AUDIO_PROBE_MATRIX.md`、`docs/DEVICE_COMPATIBILITY.md`、`docs/DECISION_LOG.md`、`docs/NEXT_TASKS.md`。文档中的所有验证状态默认为 `UNVERIFIED`，除非有真实运行证据。

### 8.2 测试体系

- unit: 语言方向/去抖、Utterance 状态机、流分包重组、事件幂等、数字术语纠错、设备能力状态机。
- integration: 使用 Mock PCM sine/speech fixtures 的提供商流与音频渲染；模拟断网/重连/乱序/重复片段。
- UI: 折叠/展开/上下翻转；PTT 按下、滑动取消、松开发送；IMЕ 编辑提交；权限拒绝/允许。
- device: vivo X Fold5 OriginOS 6（真机）、微信/WhatsApp 指定版本、蓝牙耳机 1/2/3 路、适配外设。
- stress: 30/60 分钟后台/锁屏/耳机断开/切换网络；测耗电、内存、丢帧率、录音和麦克风权限冲突。

## 9. 安全、权限及隐私

- Android 录音、蓝牙、通知、前台服务、MediaProjection、悬浮窗逐项按需申请，不“启动时要所有权限”。录音前台服务使用当前 Android 规范要求的权限/声明与可见通知；MediaProjection 由系统授权、不能持久复用用户授权令牌。
- Root 模块做 `targetPackage` 白名单、会话令牌、用户显式开关，默认 PASS_THROUGH；看门狗 1～2s 无连接就恢复 MIC（阈值可配置），用户可一键停用并卸载。
- 服务端只保存严格必要的统计；默认不长期上传或留存原始音频。若本机保存，则单独可控制期限（即时丢弃、24h、7天、30天、自定义）；敏感对话可以设置“不保存音频，只保留文本”或全部不保存。
- 云端密钥不进入 APK 代码库；错误日志禁止原始音频、客户联系人、消息正文；导出诊断日志要 SHA/脱敏设备 ID。
- 不得修改微信/WhatsApp 协议、伪造第三方账号、破坏端到端加密或偷偷收集他人通话；通话另一端的知情同意由用户完成并在 UI 中提示。
- 高权限操作的设备解锁、刷机和固件备份全部属单独审批项，Codex 不应自动执行。

## 10. 风险登记与 Go/No-Go

| 风险 | 严重性 | 验证方法 | 决策 |
|---|---|---|---|
| X Fold5 BL 锁定无法稳定 Root | 极高 | 真机 read-only 查询/社区资料/可靠回退 | 跳过 R2/R3，不阻塞 S2-S5 |
| 微信/WhatsApp VoIP 下行不可采集 | 极高 | 两款 App 各 5 次以上真机音频测量 | R1失败研究R2，无 Root 则标为不支持 |
| 上行注入致通话中断/无声音 | 极高 | 目标进程 Hook + 另一部手机验音 | 失败就禁用，恢复原 MIC |
| 蓝牙 MIC 与手机扬声器无法分路 | 高 | BT 设备组合与 `getRoutedDevices` | 退化单路输出或手机MIC |
| 两副 BT MIC 无法同时独立采集 | 高 | 两路 PCM 时间戳/语音互相独立证据 | Tier0/1，研究多通道外设 |
| 翻译播报被识别为原声 | 高 | 循环回声测试 | AEC + Echo Guard + 禁用自动播报 |
| 非洲法语口音、数字/否定翻错 | 高 | 人工中法评测集 | 术语/数字专项确认 + 展示原文 |
| OriginOS 后台杀进程/权限断开 | 中高 | 30~60分钟锁屏/切网 | 前台服务 + 恢复状态 + 用户引导 |
| 云端模型不可用/涨价/地区限制 | 中 | Provider capability probe | 自动降级、可切换服务商 |
| Root 模块系统安全/第三方反 Root | 高 | 独立测试机、崩溃/安全检查 | 不注入主力设备生产环境 |

## 11. 决策与开放参数

已定：单台 X Fold5、OriginOS 6、中文↔法语默认、云端优先、单人可用蓝牙耳机、允许研究 Magisk/Root、文本/语音两种回复、多耳机设计要求。

待 Codex 能力探测或用户自然补充（不阻塞工程）：完整 ROM Build 与 Android API、耳机品牌型号及 LE Audio 支持、是否具有稳定的 Root 环境、模型密钥/服务部署位置、是否接受 USB-C 音频接收器。采用可配置默认并让系统明确显示 `UNVERIFIED`。

## 12. 官方技术与可研究代码参考（日期：2026-11-03）

**官方权威来源**

1. Android Playback Capture 限制：<https://developer.android.com/media/platform/av-capture>
2. Android 并发麦克风采集：<https://developer.android.com/media/platform/sharing-audio-input>
3. Android 16 兼容性/并发：<https://source.android.com/docs/compatibility/16/android-16-cdd>
4. Android 多设备音频路由（系统特权）：<https://source.android.com/docs/core/audio/combined-audio-routing>
5. Android LE Audio：<https://developer.android.com/develop/connectivity/bluetooth/ble-audio/overview>
6. Android BLE 音频及通信设备选择：<https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager>
7. Android 多耳机 Audio Sharing：<https://support.google.com/android/answer/16550669?hl=en>
8. vivo X Fold5 官方参数：<https://www.vivo.com.cn/vivo/param/xfold5>
9. OpenAI 实时翻译 API：<https://developers.openai.com/api/docs/guides/realtime-translation>
10. Gemini Live Translation API：<https://ai.google.dev/gemini-api/docs/live-api/live-translate>
11. 千问实时翻译 API：<https://docs.qwencloud.com/developer-guides/speech/realtime-translation>

**社区实验项目：仅作为审计与对照，不代表本机可用**

1. ShizuCallRecorder（Shizuku/Shell 录音研究）：<https://github.com/kitsumed/ShizuCallRecorder>
2. CallVault（本机 ADB 高权限录音研究）：<https://github.com/Ph1nt0m-oss/Callvault>
3. XMicInject（LSPosed `AudioRecord.read` 虚拟麦克风）：<https://github.com/n00b-labs/XMicInject>
4. PhantomMic（LSPosed native 麦克风注入）：<https://github.com/Mino260806/PhantomMic>
5. GlassMic（Java AudioRecord/AAudio Hook 研究）：<https://github.com/Xposed-Modules-Repo/io.mo.glassmic>

---

## 附录 A：关键禁止推断

- `Bluetooth 5.4` ≠ `Auracast 输出支持` ≠ `多副耳机麦克风同时录音`。
- `AudioRecord.startRecording()` 成功 ≠ `PCM 非静音`。
- `MediaProjection` 授权 ≠ `可捕获微信/WhatsApp 通话`。
- `Shizuku/ADB shell` ≠ `系统签名权限`。
- `Root` ≠ `能解密/截获一切 App 音频`，也不要求绕过 E2EE；音频只能从本端获得授权的播放/录音链路研究。
- `XMicInject/PhantomMic README 显示可用` ≠ `X Fold5 OriginOS 6 当前微信版本实测通过`。
- `本机扬声器播放翻译` ≠ `通话远端收到译音`。
- `两副耳机均连接` ≠ `两副都可同时输出` ≠ `两副都可同时收音`。
- `纯自用` ≠ `无隐私与通信合规责任`。

## 附录 B：完成定义（Definition of Done）

每项功能都必须有实际代码、编译与测试记录、明确状态、README/文档更新、可复现步骤、无权限时的正确降级。高权限实验额外要求用户明确启用/禁用、崩溃时原麦克风恢复、从未授权包名中无 Hook、对端声音真实验证。未通过时写 `BLOCKED/UNVERIFIED`，不得标为 Done。
