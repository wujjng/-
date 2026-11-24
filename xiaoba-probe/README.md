# 小八 M1 可行性探针（XiaobaProbe）

**目标设备**：京鱼座 i8 Pro，Android 8.1（API 27），ARMv7 32 位（armeabi-v7a），已用 Magisk root。
**用途**：在写业务代码之前，先回答交接文档 §0 / §5.6 / §7-M1 的四个问题：

| # | 要回答的问题 | 对应按钮 |
|---|---|---|
| 1 | 内存、zram、CPU 基线 | ① 设备信息 |
| 2 | 能不能拿到原始 4 麦 / 回采通道（情况 B） | ② 多声道扫描、④ Root 探查（tinycap） |
| 3 | 系统/硬件有没有可用的 AEC（情况 A） | ③ 回声消除测试 |
| 4 | sherpa-onnx KWS 在 ARMv7 上的 CPU、内存、唤醒率、播报中打断率 | KWS 区 |

---

## 一、编译（二选一）

> 工程依赖 sherpa-onnx 的预编译 `.so`、同版本的 Kotlin API 源码和 KWS 模型，这三样**不入库**，由 `scripts/fetch_deps.sh` 自动下载。

### A. GitHub Actions 云端编译（推荐，电脑上不用装任何东西）
1. 新建一个 GitHub 私有仓库，把本目录全部内容推上去（包括 `.github/`）。
2. 打开仓库的 **Actions** 页，`build-probe-apk` 会自动运行（也可以点 *Run workflow* 手动触发）。
3. 运行完后在这次运行的页面底部下载 `xiaoba-probe-debug-apk`，解压得到 `app-debug.apk`。

### B. 本地 Android Studio
1. 先在 Git Bash / WSL / macOS 终端里执行：`bash scripts/fetch_deps.sh`
   - 国内网络访问 GitHub 慢时：`GH_MIRROR=https://<你的镜像前缀> bash scripts/fetch_deps.sh`
2. 用 Android Studio 打开本目录（首次打开会自动生成 Gradle Wrapper），然后 Build → Build APK。
3. 要求 JDK 17。

> 如果 sherpa-onnx v2.0.0 的下载地址变了：`SHERPA_VERSION=<新版本号> bash scripts/fetch_deps.sh`。`.so` 和 Kotlin API 必须是同一个版本。

## 二、安装
```bash
adb connect <音箱IP>:5555      # 或者插 USB
adb install -r app-debug.apk
```
桌面上会出现「小八探针」，第一次打开时允许录音权限。

## 三、测试流程（重要：要测两轮）

**第 1 轮：原厂语音服务还开着（先不要冻结 `com.joyfish.voiceprobe`）**
1. 依次点 ① ② ③ ④（④ 会弹 Magisk 授权，点允许）。
2. 再对音箱喊几次「嗨小京鱼」，确认原厂还能正常唤醒。
3. 点「保存报告到文件」。

**第 2 轮：冻结原厂语音服务后**
```bash
adb shell pm disable-user --user 0 com.joyfish.voiceprobe
```
1. 重新打开探针，依次点 ① ② ③ ④。
2. **KWS 测试**（默认唤醒词「小爱同学」，录音源先用 VOICE_RECOGNITION）：
   - 点「▶ 开始 KWS」，等右上角显示"运行中"。
   - 在 1 米、3 米、5 米处各喊 10 次，记录命中次数（右上角绿字会实时计数，换距离前点"试喊计数清零"）。
   - 勾选"循环播放干扰音"后重新开始 KWS，在播放过程中喊唤醒词，测打断命中率。
   - 不说话放 10 分钟，看有没有误唤醒，同时记下状态栏里的 CPU / RSS。
   - 把录音源换成 VOICE_COMMUNICATION / MIC 再测一轮，对比效果。
3. 点「保存报告到文件」。

**恢复原厂**：`adb shell pm enable com.joyfish.voiceprobe`

## 四、把结果交给接手 AI
```bash
adb pull /sdcard/Android/data/com.xiaoba.probe/files ./xiaoba_probe
adb pull /data/local/tmp/xiaoba ./xiaoba_probe/tinycap_raw   # 如果 ④ 抓到了文件
```
把整个 `xiaoba_probe` 文件夹打包发过来，同时附上你手动记录的命中次数。

## 五、判读速查

| 现象 | 结论 | 后续 |
|---|---|---|
| ③ 某个录音源的回声残留比 MIC 低 ≥15 dB | 系统/硬件有 AEC（情况 A） | 直接用这个录音源，播报中打断可以提前做 |
| ② 或 ④ 拿到 ≥4 路，各路不同，其中有一路和播放内容高度相关 | 原始多麦 + 回采（情况 B） | 自建 WebRTC APM 前端 |
| 冻结 voiceprobe 前录音全零，冻结后正常 | 原厂进程独占麦克风 | 冻结清单正确 |
| 两轮都没有 AEC、也拿不到多路 | 情况 C | 按交接文档 §3.1 P1 讨论是否保留原厂唤醒 |
| KWS 状态栏 CPU > 25% 或 RTF > 0.3 | 32 位上偏重 | 换 chunk-8、调参数，或评估 WeKws 专用小模型 |

## 六、自定义唤醒词（探针版）
在 KWS 区输入**带数字声调的拼音**和显示名，例如 `xiao3 ba1 xiao3 ba1` + `小八小八`，然后点"添加唤醒词"，再重新开始 KWS。
- 探针会把拼音转成模型 token（如 `x iǎo b ā x iǎo b ā @小八小八`），并逐个检查模型认不认识。
- 最多 3 个；只有 2 个字会提示误唤醒风险。
- 正式版会支持直接输入汉字（离线拼音库 + 多音字选择，见交接文档 §5.7）。

## 目录结构
```
xiaoba-probe/
├── .github/workflows/build.yml   # 云端编译
├── scripts/fetch_deps.sh         # 下载 .so / Kotlin API / KWS 模型
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/com/xiaoba/probe/
        ├── MainActivity.kt   # 界面（纯框架 View，无 AndroidX）
        ├── KwsService.kt     # 前台服务：常驻录音 + KWS + CPU/RSS/RTF 采样
        ├── KwsEngine.kt      # 模型选择、唤醒词校验、sherpa-onnx 初始化
        ├── Pinyin.kt         # 带调拼音 → KWS token
        ├── AudioScan.kt      # 录音源/多声道扫描、回声(AEC)测试、录 WAV
        ├── RootProbe.kt      # su：声卡/混音器/原厂算法库/tinycap
        ├── DeviceInfo.kt     # 设备、内存、zram、音频设备
        ├── BgPlayer.kt       # 类人声干扰音播放
        ├── AudioUtil.kt      # RMS/相关系数/WAV 读写/测试信号
        ├── ProcStats.kt      # /proc 采样
        └── Report.kt         # 日志与报告
```
