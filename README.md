# ScreenPulse 瞬录

一款面向个人使用的 Android 录屏应用。支持全屏与区域录制、系统声 / 麦克风收音、悬浮窗控制，以及本地预览、裁剪和导出。

当前版本：**2.1.0**

本软件仅供个人爱好使用。未经开发者本人同意，不得用于二次开发或商业用途。

## 功能

- 全屏录制、自定义区域录制
- 分辨率 / 帧率 / 码率可调，支持智能码率与手动码率
- 三种收音：仅系统声、仅麦克风、系统声 + 麦克风
- 悬浮窗：开始、暂停、停止、截图、标注
- 倒计时开录、物理快捷键、画中画
- 可选文字 / 图片水印
- 本地视频列表：预览、裁剪、导出、分享、覆盖原片
- 主题：跟随系统 / 浅色 / 深色
- 中英双语
- 设置页应用内检查更新、下载并安装

## 环境要求

- Android 8.0（API 26）及以上
- 系统内录需要 Android 10+
- JDK 17
- Android Studio Hedgehog 或更新版本
- Gradle 通过项目自带 Wrapper 运行

## 构建

```bash
# 调试包
./gradlew :app:assembleDebug

# 正式包（需自行配置签名）
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/`。

发布签名把 `keystore.properties` 放在仓库根目录，内容示例：

```properties
storeFile=/path/to/your.jks
storePassword=******
keyAlias=your_alias
keyPassword=******
```

不要把密钥文件提交进仓库。

## 使用说明

1. 首次使用授予录屏、麦克风、通知权限。
2. 悬浮窗、区域框选需要「显示在其他应用上层」权限。
3. 首页选择全屏或区域后开始录制；也可用悬浮窗控制。
4. 录制结束后在视频列表预览、裁剪、导出或分享。
5. 设置页可调整画质、音频、倒计时、水印、保存路径和主题。

系统内录、悬浮窗、无障碍快捷键等能力依赖系统权限，部分机型还需要关闭电池优化。

设置页「关于」可检查更新：应用会读取 GitHub Releases，在应用内下载 APK 并调起系统安装器，不会打开外部下载页。发布新版本时在仓库创建 Release，`tag` / `versionName` 需高于当前版本，并附带 `.apk` 资源。

## 项目结构

```text
app/src/main/
  java/com/screenpulse/
    ui/                 Compose 页面：首页、设置、视频列表、预览、裁剪
    service/            录屏前台服务、区域裁剪渲染
    floatingwindow/     悬浮窗、倒计时、标注、画中画
    edit/               本地裁剪 / 导出
    compress/           后台压缩任务
    repository/         设置与数据模型
    jni/                NDK 桥接
  cpp/                  混音、降噪、帧裁剪
  res/                  资源与中英文字符串
```

技术栈：Kotlin、Jetpack Compose Material3、MediaProjection、MediaCodec、Foreground Service、WorkManager、NDK。

## 权限

| 权限 | 用途 |
|------|------|
| 屏幕录制（MediaProjection） | 捕获屏幕画面与系统音频 |
| 麦克风 | 人声录制 |
| 悬浮窗 | 桌面控制条、区域框选 |
| 通知 | 前台服务保活 |
| 相机 | 画中画 |
| 无障碍（可选） | 物理快捷键 |
| 网络 | 检查更新并下载安装包 |
| 安装未知应用 | 应用内安装更新 |

## 关于

产品名称：ScreenPulse 瞬录  
应用包名：`com.screenpulse`  
最低系统：Android 8.0  
目标系统：Android 14

更完整的产品与技术方案见：

- `docs/ScreenPulse‑瞬录‑产品需求文档.md`
- `docs/ScreenPulse‑瞬录‑技术落地文档.md`

## 声明

本软件由个人爱好维护，功能以当前代码为准。

未经开发者本人同意：

- 不得二次开发、分发修改版本
- 不得用于商业用途
- 不得将本项目作为闭源产品的基础

使用录屏功能时请遵守所在地区法律法规，以及被录制内容的版权、隐私与平台规则。
