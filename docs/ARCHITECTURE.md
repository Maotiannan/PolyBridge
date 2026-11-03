# 工程结构

| 边界 | 职责 |
|---|---|
| core / AudioEvidence | 音频能量、有效样本、静音与结束原因、实际端口和诊断记录 |
| core / ProbeReport | 有版本的报告、构建指纹摘要、JSON 与 Markdown。不包含原始音频 |
| core / Conversation、SpeechTranslation | 录音、识别、翻译与纠错状态，恢复中断、区分当前会话与历史；供应商无关的识别、翻译、合成接口及单声道 WAV |
| provider / qwen | 百炼地区与业务空间、识别和翻译请求、自然声音合成、超时和错误分类。带签名的音频地址仅用于下载，下载不携带 API Key |
| app / audio | 音频设备与蓝牙协议、Android 录音和播放接口、用户启动的前台采样服务 |
| app / PolyBridgeApp | 同一时间只开放一个采样会话，结束结果按会话标识接收。已完成诊断与最近对话写入应用私有存储 |
| app / ConversationTranslator、TranslationSettings | 在应用进程处理松手后提交的句子，保留已识别的原文供重试；凭据用 Android Keystore AES-GCM 加密，不进入导出报告 |
| app / ui | 对话、离线词本、历史和设置；宽屏旋转分区；系统语音在千问朗读不可用时提供降级。词本读音随应用安装 |
| app / MainActivity | 用户权限、逐次系统播放捕获授权、服务启动和系统文件选择器导出 |

采样开始前记录可恢复的中断状态。正常结束时同时更新会话和诊断记录。滞后的重复回调不会覆盖新会话。保留最近 40 条诊断和 100 条对话。0.1.x 历史升级后仍可查，旧示例保留标签。只有面对面按住期间暂存最长 30 秒 PCM，取消、静音和错路由均不会上传。进程被杀后只保留已有文字。

普通麦克风和播放捕获共用度量与结束判定。前台服务只带本次需要的服务类型，麦克风和播放共享由不同的用户动作启动。退出或系统撤销后释放录音与共享对象。报告中最近一次测试音的实际输出和每次采样的实际输入各自独立。

[Android 播放捕获](https://developer.android.com/media/platform/av-capture) · [系统共享授权](https://developer.android.com/media/grow/media-projection) · [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)

## 自由监听、消息与声音输出

| 模块 | 处理范围 |
|---|---|
| core / LiveConversation、ReconnectingLiveSession | 实时字幕、最终译文、终止原因和有上限的连接恢复。旧连接的迟到事件与中断期间的声音不进入新会话 |
| provider / qwen / QwenLiveSession、RealtimeEvents | 16 kHz 音频经两个固定目标语言的连接发送。结合源语言识别、句子和响应编号归集译文与 24 kHz 译音，只提交匹配的翻译方向 |
| app / audio / LiveDesk | 显式录音、转录、回放与打断。扬声器朗读时将送入云端的帧替换为静音，耳机路由得到确认后允许声音打断 |
| app / audio / PlaybackRouting、HeadsetPreferences | 对实际机身路由按系统音量曲线衰减，其他输出保持原音量。设备分配使用每次安装独有的散列标识。指定耳机不匹配时不播放该句 |
| core / ImportedAudio，app / audio / AudioFileDecoder、TranslatedAudioShare | 系统选中的音频重采样为 16 kHz，原录音只在内存处理。分享时在私有缓存创建译音文件并仅授予读取权限，启动或下次分享时清理超过一天的文件 |
| app / ime、ui / VoiceTools、CallAssistPanel | 校对并插入当前编辑框，显式共享媒体，查看目标应用和复制文字回复。输入法独立按系统生命周期处理，私密编辑框禁用翻译 |

自由监听、长按和系统播放共享互斥。语音消息共享只申请所选应用允许采集的媒体用途，通话音轨仍须单独验收。
