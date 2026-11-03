# 工程结构

| 边界 | 职责 |
|---|---|
| core / AudioEvidence | 音频能量、有效样本、静音与结束原因、实际端口和诊断记录 |
| core / ProbeReport | 有版本的报告、构建指纹摘要、JSON 与 Markdown。不包含原始音频 |
| core / Conversation | 按住说话的单会话归属、取消、重复结束和中断恢复，中法演示管线 |
| app / audio | 音频设备与蓝牙协议、Android 录音和播放接口、用户启动的前台采样服务 |
| app / PolyBridgeApp | 同一时间只开放一个采样会话，结束结果按会话标识接收。已完成诊断与最近对话写入应用私有存储 |
| app / ui | 音频诊断四页、紧凑屏幕双角色和宽屏幕旋转分区、示例重播 |
| app / MainActivity | 用户权限、逐次系统播放捕获授权、服务启动和系统文件选择器导出 |

采样开始前记录可恢复的中断状态。正常结束时同时更新会话和诊断记录。滞后的重复回调不会覆盖新会话。保留最近 40 条诊断和 100 条对话。卸载会删除本地记录。

普通麦克风和播放捕获共用度量与结束判定。前台服务只带本次需要的服务类型，麦克风和播放共享由不同的用户动作启动。退出或系统撤销后释放录音与共享对象。报告中最近一次测试音的实际输出和每次采样的实际输入各自独立。

[Android 播放捕获](https://developer.android.com/media/platform/av-capture) · [系统共享授权](https://developer.android.com/media/grow/media-projection) · [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
