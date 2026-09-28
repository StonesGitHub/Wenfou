# 问否（Wenfou）

安卓优先的 AI 问答分享社区。本仓库当前是首个开发切片，还不能对公网提供服务。

当前实现：

- server/wenfou/core.py：私密会话、模拟回答、选段提交、审核状态、公开快照、私密分叉。
- server/tests/test_core.py：覆盖未发布不可读、越权、选段归属、分叉隔离与撤回。
- contracts/api-draft.yaml：安卓首版与后续小程序共用的首批接口草案。
- clients/android/：可由 Android Studio 打开的原生 Kotlin/Compose 工程，包含发现、提问、发布预览和个人入口；当前使用本地样例数据。

安卓工程使用 JDK 17、Android SDK 36。用 Android Studio 打开 clients/android，安装 SDK 后运行 app 的 debug 配置。命令行可在该目录运行：

    bash gradlew :app:assembleDebug

在 server 目录执行以下命令验证领域逻辑：

    python3 -m unittest discover -s tests -v

本地样例回答并非模型生成。后续接入登录、HTTP/流式接口、PostgreSQL、模型适配、运营审核和真机验证。核心约束：私聊按所有者校验；公开读取只来自快照；发布由用户选取片段并确认；分叉仅复制公开快照。

GitHub Actions 的 `CI` 工作流执行领域测试和 Android debug 构建，成功后可从运行页面的 `wenfou-debug-apk` 产物下载 APK。2026-09-27 的首轮构建已通过；尚未做真机交互验收。当前环境没有 Android SDK，故本地未运行 Android 构建。
