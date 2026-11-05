# GitHub 发布与维护

## 首次公开 · 2026-11-05

用户要求原文：

> 就这个版本吧，推到github上去
>
> 新建一个公开仓库即可

公开仓库：[Maotiannan/PolyBridge](https://github.com/Maotiannan/PolyBridge)。默认发布分支为 `main`，本地继续保留 `codex/caption-scroll-recovery` 开发分支。首次公开基于应用0.6.5和音频评测提交 `afce129`，仅补公开入口和本说明，不更换降噪/千问模型、不修改手机配置、不新增修复或升级安装。

保留源码、原始需求、版本记录、自动化测试、已署名的固定音频和脱敏证据。此仓库公开可见；本轮没有替项目选择新的开源许可证，随附第三方模型/素材继续遵循各自许可，见 [素材说明](AUDIO_ASSETS.md)。

## 发布前核验

- 对27次已有Git提交运行Gitleaks 8.30.1，零检出；对 `git archive HEAD` 导出的当前跟踪文件运行同一工具（包含压缩文档检查），零检出。
- 额外检查常见密钥格式、业务空间标识、设备序列号、私钥和凭据文件名；查看10张跟踪截图，内容为应用界面与固定测试语句，未发现私人聊天或可见凭据。自动检查并非绝对安全保证。
- 整个目录初扫中出现5条提示，均位于被忽略的构建缓存/第三方依赖示例，不在待上传文件或Git历史中；改为精确扫描Git导出内容后零检出。扫描报告留在忽略的 `build/publish-audit/`，不公开本地缓存。
- 历史中最大文件约1.29MB，不需要把APK、工具依赖或构建缓存塞入Git。
- 发布前重新执行本地测试/Lint任务成功，未变内容使用有效缓存；此前173项电脑测试、9项Python测试、Fold520组音频对照的结论与限制见 [短音频报告](SHORT_AUDIO_TESTS.md)。

## 首次云端检查失败与修复

用户反馈原文：「failed了」。[首次运行](https://github.com/Maotiannan/PolyBridge/actions/runs/41954180050) 的9项Python测试成功，但安装Android SDK时返回 `sdkmanager: command not found` / exit 127。实际运行镜像为Ubuntu26.04.1，不是本地Windows构建环境；应用编译与模拟器检查均尚未开始。

工作流固定为Ubuntu24.04，并在使用 `sdkmanager` 前显式运行 [setup-android](https://github.com/android-actions/setup-android)，锁定v4提交 `be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd` 和命令行工具16.0（12266719）。新增 `tools/test_ci_workflow.py` 的2项静态检查，先失败再通过；这些只检查配置约束，真实云端运行成功才算修复验收。原有测试未跳过、未降低标准，应用版本和行为不变。重新推送后继续检查完整构建和模拟器结果。

## 后续维护

1. 开发前先 `git fetch origin`，核对远端 `main`；保留本地未提交更改，不强制覆盖或改写已公开历史。
2. 源码、README、CHANGELOG、测试和相关文档一起更新；版本唯一来源仍是 `gradle/libs.versions.toml`。
3. 现有 `.github/workflows/android.yml` 在推送/PR时自动运行电脑测试、Lint、APK构建、Android模拟器检查，并上传测试产物。真实千问评测仍需显式授权和费用边界，不向公开CI提供个人密钥。
4. 在线状态以 [GitHub Actions](https://github.com/Maotiannan/PolyBridge/actions) 为准。历史文档“无remote/未执行远端CI”的描述保留为当时快照，不代表此次首次公开后的状态。
5. 本次只发布Git仓库，不创建GitHub Release，不上传个人手机APK或签名密钥；如需公开下载版本，应另行说明签名、安装方式与验证边界。
