# 恢复流式字幕实施计划

> **For agentic workers:** 使用 superpowers:executing-plans 在当前分支顺序执行；用户禁止自动子代理与新工作目录。

**Goal:** 撤销 0.6.1 等整段结束才显示的体验回归，发布可覆盖安装的 0.6.2；自由监听降噪独立验证，不拖累字幕。

**Architecture:** 恢复 LiveDesk 直接接收云端预览。完成的原文仍可提前持久化，但历史不能遮住正在增长的译文。保留明确句子关联、单调排序、异常有界补译。删除被否决的稳定发布拦截器及过时断言。

**Tech Stack:** Kotlin、Robolectric、JUnit、本地 HTTP/WebSocket、Android。

## 用户原话与取舍

> 这个版本我直接发送到手机上安装了，要28s的语音结束后才开始翻译，你还是改回上一版吧，体验不好。 那些降噪算法可以用在实时监听的地方，自由监听模式下容易被环境音打断，而且会翻译错误的环境音

执行用户已明确的回退方向，不再次要求逐步批准。完全回退源码会丢失已证实的句子关联修复；继续隐藏草稿不符合最新反馈。采用有界撤销：恢复实时发布，保留不阻碍预览的正确性修复。麦克风降噪另立验证，不以新算法试验阻挡字幕回退。

## 任务 1：字幕首字与逐步更新回归

- [x] 在 `app/src/test/java/dev/polybridge/app/LiveDeskStableCaptionTest.kt` 改为原文和译文草稿立即可见，增加 28 秒输入未停止前多次更新断言，覆盖 `record` → `transcriptRows` → `CaptionContent`。
- [x] 运行 `:app:testDebugUnitTest --tests '*LiveDeskStableCaptionTest'`，确认失败是预览被隐藏。
- [x] `app/src/main/java/dev/polybridge/app/audio/LiveDesk.kt` 恢复 `val receiver: (LiveUpdate) -> Unit = { receive(request, it) }`；删除 `core/.../StableCaptions.kt` 和对应过时测试。
- [x] `ListeningTranscript.kt` 对已保存但仍 TRANSLATING 的行合并同 ID 预览；`CaptionContent.kt` 优先显示可用译文，不用等待文案遮住译文。
- [x] 两个 provider 回放测试不再经过 StableCaptions；历史测试继续验证 150 句持久化和顺序。补 Live35 原文完成/response.done 之前预览返回的本地 WebSocket 断言，包含明确关联元数据。
- [x] 跑相关测试，保持连续三段、响应乱序、异常补译、停止保存与重连的现有断言。

## 任务 2：交付与隔离降噪研究

- [x] 唯一版本 `gradle/libs.versions.toml` 更新为 0.6.2；README/CHANGELOG/NEXT_TASKS/STABLE_CAPTIONS 说明最新需求覆盖旧策略及用户已安装 0.6.1 的事实。
- [x] 完整模块测试、Lint、主包/测试包构建、验签；记录真实测试边界，不把合成协议模拟当作云端延迟或 WhatsApp 真机验收。
- [x] 检查 main/remote、差异与敏感信息，本地 Git 提交。无 remote 不声称推送。
- [x] 自由监听算法选择依据上游接口、包体、16kHz 适配和本项目真实雨声/中法样本；与字幕完全分流。只有音频证据达标后才接入，避免单纯提高音量阈值误删轻声。

## 验证命令

```powershell
$env:JAVA_HOME='D:\Android\Toolchains\temurin-17\jdk-17.0.19+10'
.\gradlew.bat :core:test :provider:qwen:test :evaluation:qwen:test :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

无手机时不调用私人音频、不动历史与模型偏好、不花新云评测额度。

执行结果：146 项应用/模块测试及 6 项独立降噪适配器测试通过；构建、Lint、验签完成。独立 GTCRN 初筛已运行，未接入 Android，也未把自然环境噪声列为已修复。当前分支和工作目录保留，无远程仓库；按用户要求本地提交，不发起 PR 或合并 main。
