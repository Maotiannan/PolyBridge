# 人声检测模型与测试录音

## 随应用安装

- 人声检测使用 Silero Team 的 Silero VAD v6.2，锁定上游提交 `be95df9152c0d7618fa1edfeb296fc3dae32376f`。本项目使用其中的 16 kHz opset 15 模型，原始权重未修改，SHA-256 为 `7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49`。模型和 MIT 许可一起随 APK 安装。[模型源码与权重](https://github.com/snakers4/silero-vad/tree/be95df9152c0d7618fa1edfeb296fc3dae32376f) · [随包许可](../app/src/main/assets/speech-detection/LICENSE.txt)
- Android 使用 Microsoft ONNX Runtime 1.23.2 的 CPU 实现。对应的 MIT 许可及上游第三方声明一起随包安装。[上游版本](https://github.com/microsoft/onnxruntime/tree/v1.23.2) · [随包许可](../app/src/main/assets/speech-detection/LICENSE-onnxruntime.txt) · [第三方声明](../app/src/main/assets/speech-detection/ThirdPartyNotices-onnxruntime.txt)

## 仅随安卓测试包安装

两段五秒雨声来自 Karol J. Piczak 整理的 ESC-50 数据集的 ESC-10 子集，固定版本为 `33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6`。两条原始 Freesound 录音均为 CC0，ESC-10 合集按 CC BY 3.0 署名，测试包包含作者和出处。测试文件从 44.1 kHz 转成 16 kHz、单声道 PCM，部分测试在内存中改变音量后与词本录音混合。[ESC-50 版本与署名规则](https://github.com/karolpiczak/ESC-50/blob/33c8ce9eb2cf0b1c2f8bcf322eb349b6be34dbb6/LICENSE) · [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)

| 测试文件 | 原始录音 |
|---|---|
| `1-21189-A-10.wav` | uberhuberman 的 Louisiana Rain 1，[Freesound 21189](https://freesound.org/people/uberhuberman/sounds/21189/) |
| `2-73027-A-10.wav` | j1987 的 rain_on_greenhouse，[Freesound 73027](https://freesound.org/people/j1987/sounds/73027/) |
| `breathing-fr.wav` | 项目词本中的两段法语（买票、问车站），中间加入 0.8 秒含合成呼吸声的换气间隔并叠加上述测试雨声。用于检查同一条媒体内能否保留前后两个意思 |

## 仅供电脑端自动回归

`app/src/test/resources/audio/train-time-zh.wav` 是 2026-11-04 固定法语问题经手机中的千问服务返回的中文译音「下一班火车几点发车？」。它来自本项目受控样本测试，不含现场录音或个人会话。24 kHz、单声道 PCM；SHA-256 为 `b6bcfd2aa218cc5e8efbd382db1e3f8f0d30348b6b93e7632d6c33e924762d2a`。用于检验低音量中文辅音不会从回声处理中漏出，也不随正式 APK 发布。
