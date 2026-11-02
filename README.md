<p align="center"><img src="./ASRTestII-icon.png" width="104" alt="ASRtestII应用图标"></p>

<h1 align="center">ASRtestII</h1>
<p align="center">用Android手机离线转写中英文录音，按时间和说话人阅读对话。</p>
<p align="center">
  <a href="https://github.com/Roylyl/ASRtestII/releases/latest"><img src="https://img.shields.io/github/v/release/Roylyl/ASRtestII?style=flat-square&amp;display_name=tag&amp;label=Release&amp;color=2563eb" alt="最新发行版本"></a>
  <a href="#安装与转写"><img src="https://img.shields.io/badge/Android-8.0%2B-27754d?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8.0及以上"></a>
  <a href="#安装与转写"><img src="https://img.shields.io/badge/ABI-arm64--v8a-526479?style=flat-square" alt="arm64-v8a架构"></a>
  <a href="https://github.com/Roylyl/ASRtestII/releases"><img src="https://img.shields.io/github/downloads/Roylyl/ASRtestII/total?style=flat-square&amp;label=Downloads&amp;color=2563eb" alt="发行附件累计下载次数"></a>
  <a href="https://github.com/Roylyl/ASRtestII/stargazers"><img src="https://img.shields.io/github/stars/Roylyl/ASRtestII?style=flat-square&amp;label=Stars&amp;color=97752d" alt="GitHub Stars"></a>
</p>
<p align="center">
  <a href="https://github.com/Roylyl/ASRtestII/releases/latest">下载最新版</a> ·
  <a href="./使用说明.md">使用说明</a> ·
  <a href="#从源码构建">从源码构建</a>
</p>

## 安装与转写

在Android 8.0及以上的64位ARM设备上，从[最新发行页](https://github.com/Roylyl/ASRtestII/releases/latest)下载并安装APK。附件名包含版本号，例如`V1.0.0`对应`ASRtestII-1.0.0.apk`。安装包内置语音和说话人模型，首次转写无需下载权重或配置API Key。

打开底部的“测试”页，选择音频来源：

|音频来源|操作|
|---|---|
|现场对话|点击“开始识别”，允许访问麦克风，看到录音提示后开始说话。结束时点击“结束识别”，让应用处理句尾。|
|已有录音|点击“导入WAV/ZIP”，选择单个WAV或包含多条WAV的ZIP。长文件先显示当前句的即时预览，再逐行追加分人结果。|

开启说话人细分后，结果按时间顺序显示。下面是格式示例：

```text
[00:01.00–00:03.00]说话人1：周五能完成测试吗？
[00:03.20–00:05.10]说话人2：可以，我周四核对结果。
```

文件要求：16位PCM WAV，16kHz、44.1kHz或48kHz，单声道或双声道。单条录音上限为20分钟和50MiB，两项均须满足。人物编号可能在整份录音处理完后调整；短答和重叠发言可能显示为待确认。

## 热词与说话人

|想做什么|在哪里设置|
|---|---|
|提高专业词的识别机会|在“热词”页选择领域词包，或逐行添加自定义中英文词。每组可选默认、轻度、中度、高四档权重。权重影响解码时的候选词选择。|
|把连续对话分给不同的人|在“设置”开启VAD分段、本地声纹区分和连续说话人细分。这三个开关默认开启。VAD定位人声，pyannote切分发言，CAM++或ERes2NetV2提供声纹特征。|
|让已知说话人显示姓名|在“声纹→声纹管理”中录制、命名并保存声纹。识别时会匹配登记记录；姓名属于模型判断，可以对照录音核实。|

中英文转写使用X-ASR1920ms。Silero和TEN VAD支持录音与文件；FSMN是实验选项，仅支持60秒以内的离线文件。更多模型设置、归组阈值和ZIP限制见[使用说明](./使用说明.md)。

## 勘误与分点摘要

1. 在“设置→云端模型”填写服务地址、API Key和模型名称。应用预填DeepSeek-V4.1-Flash的官方配置，模型ID为`deepseek-flash`；也可使用其他兼容Chat Completions的服务。
2. 转写完成后点击“开始勘误并总结”。勘误稿保留每行的时间和说话人，仅修正有上下文依据的文字。摘要再按话题整理关键问答、观点和经历。
3. 已经有勘误稿时，点击“打开已有勘误稿”，选择按上述格式保存的UTF-8文本，再点“重新生成摘要”，即可跳过录音识别和勘误。

录音识别、语音分段和声纹提取都在设备上完成。只有主动生成勘误或摘要时，应用才把转写文字和说话人标签发送给配置的云端服务。录音和声纹不会随请求上传，API Key由Android Keystore加密保存在本机。

## 应用内更新

应用启动时检查正式发行版，也会按30分钟周期检查。可在“设置”最底部点击“检查更新”手动触发。选中“下载更新”后可以查看进度，或切到后台继续下载。完成后由Android系统安装器确认安装；如果下载在后台完成，可从通知或回到应用继续。后台检查时间受网络和系统省电策略影响。

## 从源码构建

在Windows上准备PowerShell 7、JDK 17、Android SDK（含Android平台和Build Tools）及Git LFS。设置`JAVA_HOME`和`ANDROID_HOME`，然后运行：

```powershell
git lfs install
git clone https://github.com/Roylyl/ASRtestII.git
cd ASRtestII
git lfs pull
pwsh -File .\Build-Apk.ps1 -Unsigned
```

未签名构建会在Windows桌面生成`ASRtestII-1.0.0-unsigned.apk`。要在手机上安装自己的构建，请使用自己的密钥签名：

```powershell
pwsh -File .\Build-Apk.ps1 -Keystore "D:\keys\release.p12" -KeyAlias "release"
```

默认签名包名为`ASRtestII-版本号.apk`，可用`-OutputApk`指定输出路径。覆盖安装已有版本需要沿用相同证书。

<details>
<summary>维护者：发布新版本</summary>

1. 更新[AndroidManifest.xml](./app/AndroidManifest.xml)中的`versionName`，同时递增`versionCode`。
2. 用原发行证书构建签名APK，使附件名与版本一致。例如`versionName=1.0.1`时，文件名为`ASRtestII-1.0.1.apk`。
3. 在GitHub创建标签为`V1.0.1`的正式Release，上传该APK并填写更新说明。应用按三段版本号比较更新，跳过草稿和预发布版。

</details>

第三方模型与运行库的许可和来源见[模型许可目录](./app/assets/licenses/)及[FSMN说明](./app/assets/fsmn/NOTICE.txt)。
