# 自由监听降噪：独立离线评测

此目录不被 Android 模块依赖，保留独立 Python 初筛和参考实现。无麦克风、无云端请求、无私人对话，只有已入库的中法常用表达和两段雨声。0.6.3 已在 `audio/processing` 用 Kotlin 接入 GTCRN 并做真实模型回归；这里的旧初筛结果不替代正式链路或真机验证。[产品接入说明](../../docs/LISTENING_DENOISE.md)

## 实现与来源

- 上游：[Xiaobin-Rong/gtcrn](https://github.com/Xiaobin-Rong/gtcrn)，固定提交 `502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73`，模型路径 `stream/onnx_models/gtcrn_simple.onnx`，535190 字节。
- 模型 SHA256：`b4718df6228e7bdf1a8a435cf98f838636eb2fd331acabf86ba87c5192ebcb87`。许可证见本目录 `LICENSE-GTCRN.txt`。
- 频谱与缓存接口依据该提交的 `stream/gtcrn_stream.py`。本项目实现连续 512 点、256 步进、平方根 Hann 的重叠合成；边界补零、16ms 前瞻，结束只排出剩余有效样本，不裁掉句首或补出多余时间。
- 6 项适配器测试：首尾样本与长度、任意分块一致、结束前输出、空输入、确实调用频域处理器、尺度不变指标与音量保留不是同一概念。
- 指标只用于初筛：纯雨衰减、纯净语音音量和波形保真、24 组合整体与句首的尺度不变信噪指标、单线程 CPU 推理耗时。人声判断使用相同 Silero 模型和阈值，但这是独立离线实现，不是 Android SpeechGate 或 PlaybackFloor 验收。

## 自动化入口

Python 3.12；建议用单独虚拟环境安装 `requirements.txt`。模型只在明确运行下载命令时获取，默认测试不下载。依赖、模型与结果都放入已忽略的 `build/`，不会污染应用资源。

```powershell
python -m pip install --target evaluation/noise/build/python -r evaluation/noise/requirements.txt
$env:PYTHONPATH=(Resolve-Path evaluation/noise/build/python).Path
python -m unittest discover -s evaluation/noise -p 'test_*.py'
New-Item -ItemType Directory -Path evaluation/noise/build/models -Force
Invoke-WebRequest 'https://raw.githubusercontent.com/Xiaobin-Rong/gtcrn/502ebfab64da7c4a9af78dcb9c6ceef1ebb01c73/stream/onnx_models/gtcrn_simple.onnx' -OutFile evaluation/noise/build/models/gtcrn.onnx
Get-FileHash evaluation/noise/build/models/gtcrn.onnx -Algorithm SHA256
python evaluation/noise/benchmark.py --model evaluation/noise/build/models/gtcrn.onnx --output evaluation/noise/build/results/gtcrn.json
```

`StreamingEnhancer.send(samples)` 和 `finish()` 是可独立调用的流式接口；`evaluate(root, model_path)` 返回指标对象，CLI 写入 JSON。测试已先在直通实现上捕获没有前瞻和没有处理的两项失败，再替换为实际适配器并通过。

## 2026-11-04 结果与下一步

两段纯雨衰减约 34.82 / 13.87 dB；4 段纯净中法语音音量变化 -0.03～-0.17 dB；24 组合整体指标最低改善 3.09 dB，句首最低改善 1.79 dB。这些是固定素材音频指标，**不是文字准确率提升**。两段纯雨在现有 Silero 和增强后都没有触发，此样本集未复现用户实际环境误触。

电脑单线程每 16ms 音频块的推理中位约 0.86ms，95 分位约 1.25ms；不包含 Android 收音、回声、界面、云端耗时，不代表 Fold5 性能。真实结果见 [证据](../../docs/evidence/2026-11-04-noise-candidate.json)。

0.6.3 已完成可注入麦克风模块、电脑端真实回声/抢话配合、临时降音量与正式打断分开计数、轻声句首、退出/异常资源释放、16/48kHz 时钟验证并默认启用。Fold5 原生处理、自然房间和连续长测仍待设备连接后执行；不能把本目录的初筛分数写成现场翻译准确率。
