# ScreenPulse 瞬录｜技术落地文档 V1.1
文档信息
产品名称：ScreenPulse 瞬录
项目名称：ScreenPulse‑Android
Git仓库名：screen‑pulse‑android
版本：V1.1
更新：增加深色/浅色/跟随系统主题完整落地实现方案
目标：稳定、流行、内存占用低、可直接落地开发

## 一、整体技术栈总览
### 1.1 开发语言
1. **上层业务 & UI：Kotlin**（安卓官方主推，新项目主流；空安全、协程，减少内存泄漏）
2. **音视频底层核心：C++ NDK**
> 关键：画面帧、PCM音频混音、区域裁剪放在C++层处理，规避Kotlin/Java层大数组频繁GC导致录制卡顿。

### 1.2 UI框架
- UI页面：**Jetpack Compose**（现代官方UI框架，2024‑2026主流）
- ⚠️悬浮窗、状态栏视图：**原生Android View**，不使用Compose渲染悬浮窗，降低内存开销。
- 主题体系：Compose Material3，自定义绿色主题，支持跟随系统/浅色/深色。

### 1.3 Jetpack组件（只引入必要组件，控制包体积）
1. Core‑Ktx：基础扩展
2. Lifecycle：生命周期感知，防止录屏服务内存泄漏
3. ViewModel：保存录屏配置、主题配置；屏幕旋转不丢失参数
4. WorkManager：录制完成后台视频压缩任务，App退后台任务不被杀
5. Foreground Service：录屏核心前台服务，防止系统杀掉录制进程

> 不引入：Room数据库、网络库、大图加载库；本应用无网络，文件元数据直接读取本地文件。

### 1.4 系统核心API
1. MediaProjection：安卓官方录屏API，屏幕帧捕获（Android8+）
2. AudioRecord：麦克风PCM采集
3. MediaCodec：硬件音视频编码器（优先硬编码；硬编失效降级软编）
4. MediaMuxer / FFmpeg muxer：MP4封装
5. WindowManager：原生悬浮窗实现
6. 系统按键广播：自定义快捷键监听

### 1.5 NDK第三方库
- FFmpeg‑minimal：**裁剪精简版本**，只保留：音频混音、画面裁剪滤镜、视频转码压缩、MP4封装模块；剔除全部无用模块，控制so库体积与内存。
> 禁止引入完整FFmpeg，会增大包体积与内存占用。

### 1.6 禁止使用技术
1. ❌ Flutter / React‑Native / UniApp：跨端框架；二进制数据流桥接IPC开销大，GC抖动，录屏场景容易卡顿音画不同步。
2. ❌ 第三方闭源录屏SDK：大多内置广告、付费逻辑，不可控。

## 二、整体架构分层
1. **UI层**：Kotlin + Jetpack Compose（设置、参数配置、视频列表）；悬浮窗使用原生View
2. **业务逻辑层**：Kotlin + Jetpack(Lifecycle / ViewModel)；权限管理、录屏状态管理、主题配置管理、前台服务调度
3. **JNI桥接层**：Kotlin JNI；上层与NDK C++通信；**禁止传递大块图像/音频原始数据**，只传递控制参数。
4. **音视频核心层(C++‑NDK)**：屏幕帧接收、PCM音频采集、多路音频混音、画面区域裁剪、帧预处理、MediaCodec硬编码调用、MP4封装、视频二次压缩。
5. **系统API层**：MediaProjection、AudioRecord、WindowManager、文件存储。

## 三、核心模块详细设计
### 3.1 录屏服务模块 Foreground Service
- 录屏全程运行前台服务，显示常驻状态栏通知，防止系统回收进程。
- 录制开始：申请MediaProjection录屏权限；启动Surface帧流；初始化音频采集；初始化MediaCodec硬编码器。
- 录制暂停：暂停编码，保留资源不销毁。
- 录制结束：停止编码；释放Surface、Codec、音频资源；生成MP4文件；触发WorkManager后台压缩任务。
> 重点：录制结束必须完整释放全部音视频资源，防止内存泄漏。

### 3.2 音频模块（三种收音模式）
模式：仅系统声音 / 仅麦克风 / 系统声+麦克风混音
1. Android10+：MediaProjection获取系统内部音频PCM流；AudioRecord采集麦克风PCM。
2. Android8‑9：系统无内录能力，仅支持麦克风，UI做版本提示。
3. **两路PCM混音逻辑下沉C++‑NDK层**，不在Kotlin层做数组拷贝，避免GC卡顿。
4. C++层实现：人声降噪、音量增益调节、音源音量平衡。

### 3.3 区域录制实现
1. MediaProjection输出全屏Surface帧流。
2. 在C++层做帧画面矩形裁剪，输出裁剪后的画面交给MediaCodec编码器。
> ❗不要上层把画面读成Bitmap裁剪，会极高内存开销、延迟大。

### 3.4 视频压缩模块
1. 录制完成后交由WorkManager后台执行压缩任务，不阻塞UI。
2. 优先使用MediaCodec硬件转码；硬件不支持时降级使用精简FFmpeg软转码。
3. 三档压缩参数：极速 / 均衡 / 高清无损。
4. 输出MP4，替换或输出新文件；更新本地文件列表。

### 3.5 悬浮窗模块
- WindowManager实现原生View悬浮窗；悬浮窗按钮状态：空闲 / 录制 / 暂停。
- 悬浮窗UI资源引用主题color，跟随App浅色/深色主题切换颜色。
- 支持拖动、点击控制录屏、一键隐藏悬浮窗。

### 3.6 快捷键模块
- 监听系统按键广播，读取用户配置的物理按键，触发录制启停。

## 四、UI主题模块详细落地（V1.1新增）
### 4.1 技术方案：Jetpack Compose Material3 + 自定义绿色主题
1. 定义三套ColorScheme：
   - lightColorScheme：浅色主题（绿色主色体系）
   - darkColorScheme：深色主题（绿色降低饱和度，避免过亮刺眼）
   - 跟随系统：使用 `dynamicColor = false`，读取系统夜间模式状态自动切换 light/dark。

2. 主题模式持久化存储：
   - 使用 `DataStore Preferences` 保存用户选择：`theme_mode`，枚举值：
     - `FOLLOW_SYSTEM = 0`（默认）
     - `LIGHT = 1`
     - `DARK = 2`
   - ViewModel读取持久化配置，全局UI监听主题状态变化。

3. 切换逻辑：
   用户在设置页修改主题选项 → 更新DataStore → ViewModel状态变更 → Compose重组全部UI页面，**无需重启App**。

4. 悬浮窗适配：
悬浮窗是原生View，不能复用Compose主题对象；需要把当前主题（浅色/深色）持久标记，悬浮窗读取标记，选择对应颜色资源。

5. 颜色资源不要硬编码字符串，全部抽离theme资源文件。
6. 对比度校验：保证绿色文字按钮满足安卓无障碍WCAG标准。

### 4.2 最低版本兼容
- Android10以下系统夜间模式API同样支持浅色/深色切换；跟随系统模式兼容Android8.0最低版本。

## 五、内存与性能约束（硬性）
1. 原始图像帧、音频PCM数据全部C++层内存管理；尽量不向Kotlin层传递大块二进制数组，规避GC抖动造成录制卡顿。
2. 优先MediaCodec硬件编码；软编码仅做降级兜底。
3. FFmpeg必须裁剪编译，只保留需要模块，减小so库体积和运行内存。
4. Service严格管理生命周期，录制结束立刻释放Codec、Surface、Audio资源，杜绝内存泄漏。
5. 悬浮窗使用原生View，禁止Compose渲染悬浮窗，降低内存开销。
6. 压缩任务交给WorkManager，不占用主线程。

## 六、权限清单
1. `android.permission.FOREGROUND_SERVICE`：前台服务
2. `android.permission.RECORD_AUDIO`：麦克风录音
3. `android.permission.READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / Scoped Storage：文件存储，适配安卓存储分区
4. `android.permission.SYSTEM_ALERT_WINDOW`：悬浮窗权限
5. MediaProjection录屏权限：运行时动态申请系统弹窗。

## 七、工程目录简要结构
```
ScreenPulse‑Android/
├── app/
│   ├── src/main/java/com/screenpulse/
│   │   ├── ui/                 # Compose UI 页面
│   │   │   ├── theme/          # 自定义绿色主题、ColorScheme 定义
│   │   │   ├── settings/       # 设置页面（主题、录屏参数）
│   │   │   └── videolist/      # 录屏文件列表
│   │   ├── service/            # Foreground 录屏服务
│   │   ├── viewmodel/          # ViewModel：录屏状态、主题配置
│   │   ├── repository/         # DataStore 持久化：主题模式、录屏参数
│   │   ├── floatingwindow/     # 原生悬浮窗 View 实现
│   │   ├── shortcut/           # 快捷键监听
│   │   └── jni/                # JNI 接口定义
│   ├── src/main/cpp/           # NDK C++ 音视频核心
│   │   ├── audio/              # 采集、混音、降噪
│   │   ├── video/              # 帧处理、区域裁剪、硬编码封装
│   │   ├── compress/           # 视频压缩逻辑
│   │   └── ffmpeg‑wrapper/     # 裁剪 FFmpeg 封装调用
│   └── src/main/res/           # 资源、颜色、布局（悬浮窗 xml）
└── build.gradle
```

## 八、风险点与规避
1. 长时间录制内存上涨：C++层做好帧buffer复用，不要频繁new内存；录制结束释放全部Codec资源。
2. 不同厂商MediaCodec硬件编码兼容性：增加设备兼容判断，硬编失败自动降级软编。
3. 国产后台杀进程：使用Foreground Service；提示用户关闭电池优化。
4. 主题切换悬浮窗颜色不同步：悬浮窗独立读取持久化主题标记。
5. Android版本内录差异：Android8‑9无系统内录，UI做提示，隐藏系统声音选项。

## 九、测试重点
1. 录制性能：3小时连续录制，监控内存曲线，无持续内存上涨。
2. 音频三种模式，Android10+完整验证；Android8‑9降级验证。
3. 区域录制画面裁剪正确性。
4. 三档压缩：体积、画质验证。
5. 主题：跟随系统、浅色、深色切换，所有页面+悬浮窗UI全部验证；切换不重启App。
6. 各权限拒绝场景下的异常处理。
