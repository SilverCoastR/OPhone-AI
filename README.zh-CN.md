# OPhone AI

OPhone AI 是一个开源 Android 智能体运行环境。模型可以在用户授权范围内读取手机界面、操作应用、使用内置浏览器、运行受限 Shell、发起 HTTP 请求、保存小型 JavaScript 工具，并根据新证据检查任务是否真的完成。

当前支持 ChatGPT 网页会话和 DeepSeek API。对话保存在本机，切换模型平台后仍可读取，也可新建、复制或删除。

## 主要能力

- 基于无障碍服务的 Android 界面读取与操作
- 持久任务状态、检查点、事件反馈、执行回执、等待和终止
- 内置浏览器：标签页、DOM 操作、脚本执行、截图、媒体发现、缩放和独立 Cookie
- 内置 CPython 3 及标准库，AI 可通过 `python_run` 执行脚本
- 应用私有工作区、`/system/bin/sh`、HTTP 请求、下载和可复用 JavaScript 小工具
- Android 系统录屏，以及对网页中可发现媒体流的直接录制
- 应用控制白名单、本地审计日志、能力开关和可选 Root Shell

项目只依赖标准 Android API，不要求小米或其他厂商的专用框架。

## 构建

需要 Android SDK Platform 33、Build Tools 33.0.2、Gradle 5.1.1，以及兼容 Android Gradle Plugin 3.4 的 JDK。构建机还需要 Android NDK 和 Python 3.7+；首次构建会下载 python.org 官方 CPython Android 发行包（约 46 MB，校验 SHA-256）到 `.python-cache/`，`python` 不在 PATH 时可传 `-PhostPython=<路径>`。网页运行时测试需要 Node.js 18 或更高版本。

```text
gradle assembleDebug
node --test tests/runtime.test.mjs
```

应用 ID 为 `ai.ophone.bridge`，调试 APK 生成在 `app/build/outputs/apk/debug/`。

## 初次使用

1. 选择 ChatGPT 网页版或 DeepSeek API。
2. 在“设置”中只开启当前任务需要的能力。
3. 如需操作其他应用，授予无障碍权限。
4. 使用 DeepSeek 时，在模型设置中保存 API Key。
5. 新建对话并描述希望得到的最终结果。

录屏授权、应用安装等敏感行为仍由 Android 系统确认。Root 默认关闭，只有设备已经提供可用的 `su` 且用户主动开启时才可使用。

详细架构、工具、安全边界和测试方法见 [docs/README.md](docs/README.md)。

## 许可证

Apache License 2.0。
