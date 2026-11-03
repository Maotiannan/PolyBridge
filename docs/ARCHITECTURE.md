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
