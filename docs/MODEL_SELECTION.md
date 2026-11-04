# 0.6.0 · 全功能模型选择

入口：**设置 → 千问与模型**。顶栏的千问入口也会打开同一个面板。

## 使用方法

- 选「当前方案」「Audio 3.1」「LiveTranslate 3.8」或「Omni 3.8 · 实验」，再点保存，即切换整套组合。
- 展开「按功能调整」中的任意一项，可以单独设置面对面、自由监听、屏幕字幕、中文转法语、文字翻译、朗读或独立识别。选「跟随整套」撤销该项覆盖。
- 选择整套会清除单项覆盖；返回或关闭不保存草稿。保存后从下一次操作生效，不中断已经开始的任务，不重新翻译旧历史。
- 3.8 实时模型需要在「账号连接」中填写业务空间。账号和模型一次保存。未知的旧配置会报错，不默默改成默认型号。

整套不是“所有请求都调用同一个模型”。实时语音模型不兼做纯文字重译或独立朗读，因此四套的配套模型仍默认是 ASR Flash / MT Flash / TTS Flash，可以分别改。

## 实际声音与来源

TTS Instruct Flash 在本次北京账号上返回 HTTP 404 / InvalidParameter，当前不可用；选项明确标为待验证，仅供自行核验。默认仍是 TTS Flash。不能把文档列出的型号视为本账号必然可调用，也不自动换模型掩盖失败。

有匹配的当次译音时，重播和分享可直接复用；修改译文、切换相关模型或地区后不会误用旧声音，没有可复用声音时使用当前朗读模型合成。失败不会自动换成手机声音；用户可明确点「用手机语音朗读」。

功能页显示所选型号，监听中显示本次与待生效型号。点按带模型标记的历史记录可以查看来源。服务没有返回实际型号时显示未返回，不能据请求参数伪称已确认。字幕异常复核会单独注明识别来源。

## 验证方法和边界

电脑通过本地 HTTP/WebSocket 接口校验真正发出的配置、原文/译文事件、迟到响应、重复事件、语音采样率及缓存隔离。手机通过隔离设置测试保存、返回、单项覆盖与重置，并以随包固定录音检查真实服务，不使用个人聊天、环境麦克风或个人密钥导出。

新增手机接口：

```powershell
adb -d shell am instrument -w -e class dev.polybridge.app.ModelSelectionDeviceTest dev.polybridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

付费产品接口 `ProductModelDeviceTest` 必须显式传入 `productModels=true` 和白名单 `productModel`，沿用 `qwen-evaluation-20261104/budget.json` 和排他锁。只发送一段固定短录音或一句文字，不播放声音、不改个人模型设置。保守预留不等于实际费用；用量缺项时不释放预留，不清账本。运行证据见 [本轮记录](evidence/2026-11-04-model-selection.json)。

Audio 3.1、LiveTranslate 3.8 和 Omni 3.8 的质量并不相同。既有评测中 Omni 曾原样返回法语，故保持实验标识；当前会把明确未翻译的长法语返回标为异常。缺语种元数据时只在中法限定场景依据原文文字判断方向，不是通用语种识别。不能把接通短样本等同于长期稳定或性价比排名。

本轮不宣称已经实现播放器“来源停止两秒后分段”，也不宣称解决 WhatsApp 限制采集或通话对端注音。此前的字幕滚动、雨声、换气和回声测试继续保留。

## 协议依据

- [Audio 实时客户端事件](https://help.aliyun.com/zh/model-studio/fun-audiochat-client-events)：手动提交后创建响应，自动中法模式只用一个连接。
- [LiveTranslate 客户端事件](https://help.aliyun.com/zh/model-studio/live-translator-client-events)：3.5 与 3.8 使用各自配置结构；自动方向保留双连接。
- [独立朗读接口](https://help.aliyun.com/zh/model-studio/qwen-tts-api)：文字朗读与实时同传译音不是同一个请求。

实现边界、失败记录和验收结果见 [下一步](NEXT_TASKS.md)，模型设计见 [设计文档](superpowers/specs/2026-11-04-model-selection-design.md)。
