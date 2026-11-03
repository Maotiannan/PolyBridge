# PolyBridge · 折叠屏中法翻译

面向 vivo X Fold5、OriginOS 6 的个人中法翻译应用。打开就是面对面对话。合屏时两种语言的说话键并排放在拇指附近；展开后法语和中文各占一半，分别朝向对坐的人。可以说话、打字、核对原文、修改译文和重播。

Fold5 已实测机身和单副蓝牙耳机收音、面对面双向千问翻译，以及自由监听的四句中法交替语音。新版增加自由监听断线恢复、语音消息工具、翻译键盘和耳机分配。微信和 WhatsApp 的通话声音需分别在两端验收。[设备与版本验收](docs/NEXT_TASKS.md)

## 从这里开始

1. 在顶栏点「连接千问」，选择百炼账号所属地区，保存个人 API Key。它由 Android 系统密钥加密。录音只在松手且收到有效声音后发送给百炼。识别、翻译和合成使用个人账号的额度。
2. 按住「中文」或「Français」说一句，松手后看双语字幕。滑出按键可取消。也可以点「打字」直接翻译。点「纠错」可重新翻译原文或单独修改译文，「重播」会读出当前译文。
3. 在「常用表达」选见面、沟通、出行或用餐。12 组表达的中法录音随包安装，离线可听。在「历史」点「开始新对话」可清空当前桌面，已有记录保留在手机。
4. 在「设置」可选手机或蓝牙麦克风、自动朗读和对面朝向。「设备诊断」可测十秒收音、单独播放测试音、对指定应用申请播放共享、导出 JSON 或 Markdown 报告。诊断只保存音频统计，测试音及诊断录音不会发给千问。
5. 点「自由监听」后，两人可以轮流说中文或法语。点「开始监听」才会把声音发往千问。实时服务同时连接中、法两个翻译方向，使用个人账号额度。监听中的对话页保持亮屏，译音播放时可点「打断朗读」。离开此页或手动锁屏会结束收音。
6. 「工具」可导入最长两分钟的语音，再点「开始翻译」。也可以在系统允许时采集微信或 WhatsApp 播放的语音消息，最长一分钟。译文可以复制，译音文件可以分享到其他应用。「翻译键盘」可在输入框里翻译选区或语音，校对后点「插入译文」。
7. 「轻声外放」和「中法分开播放」默认开启。连接耳机后，法语低声外放给对面的人，中文沿用耳机音量。在「我的耳机」可分别指定说话用的麦克风与两种译音的耳机。指定的接收耳机未匹配时，应用显示字幕并静音。设置显示系统上次报告的实际播放设备。
8. 「通话辅助」按应用显示已安装版本，可翻译并复制文字回复。来电声音获取、译音送达远端和双耳机同时收音仍在验收清单中。

## 构建与自动化接口测试

需要 JDK 17、Android SDK 平台 36 和 Build Tools 35.0.0 / 36.0.0。将 SDK 位置设为环境变量 `ANDROID_HOME`，或写入忽略提交的 `local.properties`。在 Windows 项目目录执行以下命令。

```powershell
.\gradlew.bat :core:test :provider:qwen:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

macOS / Linux 使用 `./gradlew`。主程序位于 `app/build/outputs/apk/debug/app-debug.apk`。核心测试调用信号、报告和对话接口。安卓主机测试通过 Robolectric 运行应用和合成音频，无需手机。两者都不能替代真机听音。

手机下次接好并授权调试后，确认只连接了一台目标设备，再执行以下命令。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
adb -d shell am start -n dev.polybridge.app/.MainActivity
```

默认设备测试检查启动与只读状态，并列出输入端口。需要自动实测收音时，先在应用内授权麦克风，再明确执行下面的命令。测试会打开可见界面和前台采样通知，十秒后在命令输出中返回脱敏 JSON。加上 `-e inputPort 编号` 可指定耳机，编号来自本次设备枚举。系统播放共享仍需逐次点选授权。

```powershell
adb -d install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -d shell am instrument -w -e class dev.polybridge.app.DiagnosticsInstrumentedTest#tenSecondMicProbeWithExplicitOptIn -e captureMic true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

需要验证真实千问服务时，先在手机配置个人凭据，显式运行下列测试。它在独立的测试记录中运行 20 句随包录音，测试后移除这些记录。TTS 测试会播放两句短音。普通构建和 CI 均无需凭据。

```powershell
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.LiveTranslationTest -e liveQwen true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

已授权的同一台设备上，以下接口可独立复测自由监听、语音导入和键盘。第一条调用真实云服务，间隔 44 秒重复 20 句约需 16 分钟。键盘测试只操作本应用自己的编辑框，结束后恢复原输入法。

```powershell
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.LiveListeningTest -e liveListen true -e turns 20 -e gapMs 44000 dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
adb -d shell am instrument --user 0 -w -r -e class dev.polybridge.app.VoiceToolsDeviceTest -e liveQwen true -e testKeyboard true dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

版本目录 `gradle/libs.versions.toml` 的同一版本号生成安装包版本、系统排序编号和设置中的版本。

已获机主授权的 USB 联调可由调试端代点本项目的可见按钮。这台 Fold5 已验证可以自动选择「共享当前应用」并开始一次测试。长时间联调可临时开启 USB 供电亮屏，结束时恢复原设置。安全锁要求密码或生物识别时，由机主在手机上解锁。

## 文档导航

- [需求原文](PRD_XFold5_Translation_App_v1.0.md) · [原始启动说明](CODEX_START_PROMPT.md)
- [实施计划](docs/IMPLEMENTATION_PLAN.md) · [模块与数据流](docs/ARCHITECTURE.md) · [决策记录](docs/DECISION_LOG.md)
- [真机兼容性](docs/DEVICE_COMPATIBILITY.md) · [音频测试矩阵](docs/AUDIO_PROBE_MATRIX.md)
- [验收进度与下一步](docs/NEXT_TASKS.md) · [版本记录](CHANGELOG.md)
