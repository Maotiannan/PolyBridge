# PolyBridge · 折叠屏中法翻译

面向 vivo X Fold5、OriginOS 6 的个人中法翻译应用。打开就是面对面对话。合屏时两种语言的说话键并排放在拇指附近；展开后法语和中文各占一半，分别朝向对坐的人。可以说话、打字、核对原文、修改译文和重播。

Fold5 已实测机身和单副蓝牙耳机收音、双向千问翻译、手动耳机重播，以及改完两栏文字后播放新译文。自由监听支持按实际译音识别回声和打断。微信和 WhatsApp 的通话上下行、双耳机同时收发仍需独立验收。[各功能实测范围](docs/NEXT_TASKS.md)

2026-11-04 新增独立的 Qwen 模型评测接口：专属地址已核验 Audio 3.1、LiveTranslate 3.8 和 Omni 3.8 的实际型号；前两者保留了 15 秒停顿前后两句，Omni 此样本未正确翻译。没有更换正式模型，多分钟连续语音与产品层验收仍未完成。[初筛结果与费用边界](docs/QWEN_EVALUATION.md)

0.6.0 在「设置 → 千问与模型」支持整套切换和按功能单独选择，覆盖面对面、自由监听、屏幕字幕、中文转法语及配套识别、文字翻译和朗读。配置持久保存，下一次操作生效；显示请求与实际返回的模型，失败不静默换模型。[使用说明与验证边界](docs/MODEL_SELECTION.md)

2026-11-04 后续排查：模型切换不等于长语音可靠性完成。当前 Audio 3.1 的 0.8 秒断句、每句独立保存、过滤后音频复核和断线不补发均需分别处理。播放源停止后 2 秒合并规则尚未实现。用户参与算法与体验设计，工程配置由开发负责；GTCRN、RNNoise、WebRTC APM 已列入分场景研究候选，尚未接入或宣布优胜。[完整原理、算法候选与待确认改进](docs/TRANSLATION_PIPELINE.md)

0.5.2 在手机上先区分人声与雨声，再把人声交给千问。一次监听里的原文和译文按说话顺序保存，换气或分段后可以回看开头。在其他应用上点浮窗的「全文」即可往前翻。[已测声音与真机待验项](docs/NEXT_TASKS.md)

0.5.6 修复屏幕字幕长句停在开头：小窗和全文会自动显示最新内容，手动上翻时暂停，点「回到最新」恢复。遇到长串机械重复时，先显示核对提示，自动用临时缓存的音频重新识别语种并翻译一次；不能确认时会明确提示重放，不继续把循环文字当成正常字幕。缓存只在内存，但当前位于人声过滤之后，不能恢复已被过滤掉的声音；异常复核会额外使用千问额度。[方案与限制](docs/CAPTION_RELIABILITY.md) · [最新链路排查](docs/TRANSLATION_PIPELINE.md)

上一版 **0.5.5** 在 Fold5 通过真实千问的纯雨声、连续八组换气法语、独立应用双向悬浮字幕及停止后全文检查，电脑端 56 项测试通过。当前版本的安装与验证状态见 [真机验收](docs/NEXT_TASKS.md)。

本轮修复了固定样本中漏出的播报辅音，以及轻声抢话丢失句首的问题。普通音量和两次轻声加底噪测试均保留完整问题与译文，只生成两条记录，停播为 314～346 毫秒。播报期间突发雨声也不会绕过人声检测或误停播报。上述结果来自受控音频与真实千问，实际房间的说话距离、反射和突变噪声仍须独立验证。[本轮证据与剩余工作](docs/evidence/2026-11-04-fold5-0.5.5.json)

## 从这里开始

1. 在顶栏点「连接千问」，选择百炼账号所属地区，保存个人 API Key。它由 Android 系统密钥加密。长按说话在松手后发送这句话，自由监听和屏幕字幕在显式启动后逐段发送声音。识别、翻译和合成使用个人账号的额度。
2. 按住「中文」或「Français」说一句，松手后看双语字幕。手指可以在键上移动，上滑到取消区会变红，在红区松手取消，移回按钮再松手发送。展开后底部留有手势空区。面对面默认使用沉浸模式，可在设置里关闭。打字、纠错和手动重播都在对话页。
3. 在「常用表达」选见面、沟通、出行或用餐。12 组表达的中法录音随包安装，离线可听。「历史」在内屏并排显示双语。点「新对话」保留历史、换一页继续聊。点「清空历史」一次移除所有对话，六秒内可撤销。
4. 在「设置」可选手机或蓝牙麦克风、自动朗读和对面朝向。「设备诊断」可测十秒收音、单独播放测试音、对指定应用申请播放共享、导出 JSON 或 Markdown 报告。诊断只保存音频统计，测试音及诊断录音不会发给千问。
5. 在「自由监听」点「开始监听」，两人可以轮流说中文或法语，译音播放时可以打断。结束按钮上的音量条反映麦克风实际听到的声音，有雨声时也可能起伏。应用自动过滤背景声。监听页可向前翻看本次原文和译文，停止后仍可回看。离开此页或手动锁屏会结束麦克风监听。
6. 要听其他应用，打开「屏幕字幕」，选好声音来源，点「从应用播放中采集」。首次使用允许悬浮窗，每次按系统提示共享选定的应用。字幕条随语音更新，点「全文」可前后翻看中法对照。拖动标题可挪动字幕，点「停止」立即关窗，返回字幕页可读本次全文。系统只提供来源应用允许共享的媒体声音，遇到限制时应用会提示没有收到声音。[安卓播放采集规则](https://developer.android.com/media/platform/av-capture)
7. 音量用手机的媒体音量键调节。默认开启「中法分开播放」。连接耳机后，法语从机身扬声器播放给对面的人，中文从耳机播放。在「我的耳机」可分别指定说话用的麦克风与两种译音的耳机。指定的接收耳机未匹配时，应用显示字幕并静音。设置显示系统上次报告的实际播放设备。
8. 在「中文转法语」按住说中文、松手看法语。可以点「在本机播放法语」，也可以手动分享法语语音文件。普通电话、微信和 WhatsApp 通话的对端目前不能直接听到本机的合成法语。0.5.0 已移除翻译输入法和选择录音文件的入口。

手动重播和当场自动朗读有各自的用途。戴着耳机点「重播」时，中文、法语默认都在耳机里听。自动朗读沿用上面的分路。「纠错」同时显示两栏，点「保存修改」即在本机生效。希望千问重译时点「用原文重新翻译」。自由监听直接复用千问返回的译音，修改文字后使用新文字生成声音。

## 构建与自动化接口测试

需要 JDK 17、Android SDK 平台 36 和 Build Tools 35.0.0 / 36.0.0。将 SDK 位置设为环境变量 `ANDROID_HOME`，或写入忽略提交的 `local.properties`。在 Windows 项目目录执行以下命令。

```powershell
.\gradlew.bat :core:test :provider:qwen:test :evaluation:qwen:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

macOS / Linux 使用 `./gradlew`。主程序位于 `app/build/outputs/apk/debug/app-debug.apk`。核心测试调用信号、报告和对话接口。安卓主机测试通过 Robolectric 运行应用和合成音频，无需手机。两者都不能替代真机听音。

手机下次接好并授权调试后，确认只连接了一台目标设备，再执行以下命令。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n dev.polybridge.app/.MainActivity
```

默认设备测试检查启动、输入端口，并用隔离的记录和固定的声音样本验证监听。需要实测麦克风时，先在应用内授权，再执行下面的命令。测试会打开可见界面和前台采样通知，十秒后在命令输出中返回脱敏 JSON。加上 `-e inputPort 编号` 可指定耳机，编号来自本次设备枚举。系统播放共享仍需逐次点选授权。

```powershell
adb -d install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument -w -e class dev.polybridge.app.DiagnosticsInstrumentedTest#tenSecondMicProbeWithExplicitOptIn -e captureMic true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

需要验证真实千问服务时，先在手机配置个人凭据，显式运行下列测试。它在独立的测试记录中运行 20 句随包录音，测试后移除这些记录。TTS 测试会播放两句短音。普通构建和 CI 均无需凭据。

```powershell
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.LiveTranslationTest -e liveQwen true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

在已授权的同一台设备上，第一条以固定录音复测双向千问，间隔 44 秒重复 20 句约需 16 分钟。第二条需先允许本应用悬浮，使用独立测试播放器和真实千问验证双向字幕。第三条在展开状态检查两侧按键、音量波动和系统边缘手势。

```powershell
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.LiveListeningTest -e liveListen true -e turns 20 -e gapMs 44000 dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.ScreenCaptionsDeviceTest -e cloudCaptions true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.InputInteractionDeviceTest -e inputInteractions true -e verifyVivoDock true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

0.4.0 可以分别复测耳机重播、云端抢话和房间收音。戴一副耳机，在已配置千问的 Fold5 上运行下面的接口。第二条使用固定录音，加入 `-e softDuplex true` 会降低第二个人的音量并叠入背景声。第三条让 Windows 的独立扬声器在最低测试电平播放另一句录音，房间麦克风的采样只在手机上处理，输出仅含测量值。

```powershell
$runner = 'dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner'
adb -d shell am instrument --user 0 -w -r -e liveReplay true -e requireHeadset true -e class dev.polybridge.app.ConversationControlsTest#replayButtonDeliversBothLanguagesAndASecondTapUsesSavedAudio $runner
adb -d shell am instrument --user 0 -w -r -e cloudDuplex true -e class dev.polybridge.app.QwenDuplexDeviceTest $runner
python tools/qa_room_source.py
```

抢话测试有两分钟上限，同时检查完整问题、对应译文、记录数量及正常音量下的停播时间。模拟回声跟随播放器实际的临时静音和停止状态，音频处理耗时包含在每个 20 毫秒采样周期内，并报告处理耗时。排错时可另加 `-e captureDuplex true`，记录固定输入、回声过滤后、送给千问的三个阶段，以及云端返回的测试译音；它不打开环境麦克风。诊断放在手机应用私有缓存 `qa-duplex-*.wav` 和 `qa-duplex-frames.csv`，包含固定测试句及阶段时间，不包含密钥，默认不开启。

设备测试必须串行执行，等当前 `am instrument` 进程结束后再启动下一项。运行中不要安装 APK 或启动独立的 UIAutomator，否则会争用系统测试服务，使本轮结果无效。启动失败须单独记录，不能归为音频失败。

只在专用模拟器上运行整库清空和撤销。第二条命令在同一设备连续发送 15 分钟固定的中法重叠语音，检查回声、句首和实际停止播放的时刻。两项都可以离线运行。

```powershell
adb -e shell am instrument -w -r -e testArchive true -e class dev.polybridge.app.ConversationControlsTest#editBothLanguagesAndClearAllFromTheRealArchive $runner
adb -e shell am instrument -w -r -e testDuplex true -e duplexDurationMs 900000 -e class dev.polybridge.app.LiveDuplexDeviceTest $runner
```

版本目录 `gradle/libs.versions.toml` 的同一版本号生成安装包版本、系统排序编号和设置中的版本。

0.5.2 的离线回归会向自由监听和应用采集两个入口送入真实雨声、人声和换气停顿，检查送往识别的音频、字幕顺序、重连和重新打开后的记录。第一条使用隔离模拟器。第二条在配置千问的手机上使用固定样本验证自由监听，测试期间不外放。上面的 `cloudCaptions` 则通过独立应用的真实播放共享验证雨声和整段法语字幕。

```powershell
adb -e shell am instrument -w -r -e class dev.polybridge.app.StreamingListeningRegressionTest dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument -w -r -e class dev.polybridge.app.StreamingListeningRegressionTest#realQwenIgnoresRainAndTranslatesTheEntireBreathFixture -e cloudRain true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

在第二条命令增加 `-e cloudRainRepeats 8`，会连续验证八组带换气的法语，每组的原文和中文译文都要保留。真实千问测试会先打开应用前台，并设有超时保护。

0.5.6 的专用字幕接口同时检查 100 行长字幕的触摸上翻、自动跟随、无障碍滚动和回到最新，使用独立存储核对未确认状态，并将固定法语录音送入真实千问复核。只有受控异常事件是模拟的，复核 ASR 与翻译是真实请求，不采集房间声音。默认不读取个人历史；另加 `-e inspectCaptionQuality true` 才会在用户授权后只读检查旧异常条目，缺少旧条目时跳过，不输出原文。

```powershell
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.CaptionReliabilityDeviceTest -e cloudCaptionRecovery true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

已获机主授权的 USB 联调可由调试端代点本项目的可见按钮。这台 Fold5 已验证可以自动选择「共享当前应用」并开始一次测试。长时间联调可临时开启 USB 供电亮屏，结束时恢复原设置。安全锁要求密码或生物识别时，由机主在手机上解锁。

## 隔离的模型短样本评测

评测模块只进入测试 APK，不进入正式 APK。不要为这项工作使用会同时重装主包的 `connectedDebugAndroidTest`；只安装测试包，并在手机上保留当前正式版。普通 CI 不调用云端。

```powershell
.\gradlew.bat :evaluation:qwen:test :app:assembleDebugAndroidTest
adb -d install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.QwenEvaluationDeviceTest -e qwenEvaluation true -e evalModel ACCOUNT dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.QwenEvaluationDeviceTest -e qwenEvaluation true -e evalModel LEDGER dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

`ACCOUNT` 只检查已配置与地域，不解密凭据；`LEDGER` 只检查并纠正本轮费用日记，不联网。付费入口用 `evalModel` 指定 `AUDIO31`、`LIVE35`、`LIVE38`、`OMNI38` 或 `ASR31`，同时指定唯一 `evalRunId` 和固定 `evalFixture`（`tickets-fr/zh`、`train-fr/zh`、`breathing-fr`、`pause15-fr`）。可用 `evalWorkspace` 临时覆盖业务空间，限官方同地域主机；不修改正式设置、不把 API Key 作为测试参数。**本轮费用日记占位 7.002636 元，其中 7 元为未知项预留，不是实付；不要删除日记绕过 10 元预算。**

单次输入最多 60 秒，建连最多 15 秒、尾部等待最多 20 秒；手机系统挂起仍可能触发外层 110 秒测试超时。测试会临时把应用放到前台，结束时取消本轮的屏幕常亮；不播放声音、不采集麦克风、不读取私人聊天。安装或外部界面自动操作必须等当前 instrumentation 完全结束。输出的 `OK (1 test)` 只表示探针运行完成，必须检查结果中的错误、原文/译文、用量及语义评审，不能直接当作模型通过。

## 文档导航

- [需求原文](PRD_XFold5_Translation_App_v1.0.md) · [原始启动说明](CODEX_START_PROMPT.md)
- [实施计划](docs/IMPLEMENTATION_PLAN.md) · [模块与数据流](docs/ARCHITECTURE.md) · [决策记录](docs/DECISION_LOG.md)
- [真机兼容性](docs/DEVICE_COMPATIBILITY.md) · [音频测试矩阵](docs/AUDIO_PROBE_MATRIX.md)
- [人声检测模型与测试录音来源](docs/AUDIO_ASSETS.md)
- [Qwen 模型短样本实测、费用边界与后续选型](docs/QWEN_EVALUATION.md)
- [翻译完整链路、各环节可调项与设置改进建议](docs/TRANSLATION_PIPELINE.md)
- [验收进度与下一步](docs/NEXT_TASKS.md) · [版本记录](CHANGELOG.md)
