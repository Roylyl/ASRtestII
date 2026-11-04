<p align="center">
  <img src="./ASRTestII-icon.png" width="112" alt="ASRtestII应用图标">
</p>

<h1 align="center">ASRtestII</h1>
<p align="center">在Android手机上离线转写中英文录音，按时间和说话人阅读对话，并按需生成勘误稿与内容摘要。</p>

<p align="center">
  <a href="https://github.com/Roylyl/ASRtestII/releases/latest"><img src="https://img.shields.io/github/v/release/Roylyl/ASRtestII?style=flat-square&amp;display_name=tag&amp;label=Release&amp;color=2563eb" alt="最新发行版本"></a>
  <a href="#安装与快速开始"><img src="https://img.shields.io/badge/Android-8.0%2B-27754d?style=flat-square&amp;logo=android&amp;logoColor=white" alt="Android 8.0及以上"></a>
  <a href="#安装与快速开始"><img src="https://img.shields.io/badge/ABI-arm64--v8a-526479?style=flat-square" alt="arm64-v8a架构"></a>
  <a href="#许可证与第三方声明"><img src="https://img.shields.io/badge/Code%20license-Apache--2.0-485d75?style=flat-square" alt="项目原创代码采用Apache-2.0"></a>
  <a href="https://github.com/Roylyl/ASRtestII/releases"><img src="https://img.shields.io/github/downloads/Roylyl/ASRtestII/total?style=flat-square&amp;label=Downloads&amp;color=2563eb" alt="发行附件累计下载次数"></a>
  <a href="https://github.com/Roylyl/ASRtestII/stargazers"><img src="https://img.shields.io/github/stars/Roylyl/ASRtestII?style=flat-square&amp;label=Stars&amp;color=97752d" alt="GitHub Stars"></a>
</p>

<p align="center">
  <a href="https://github.com/Roylyl/ASRtestII/releases/latest">下载最新版</a> ·
  <a href="#安装与快速开始">快速开始</a> ·
  <a href="#主要功能">主要功能</a> ·
  <a href="./使用说明.md">详细说明</a> ·
  <a href="#从源码构建">源码构建</a> ·
  <a href="https://github.com/Roylyl/ASRtestII/issues">反馈问题</a>
</p>

ASRtestII把X-ASR、VAD和说话人处理集成到一个Android应用中，适合整理访谈、播客、会议、课程和日常对话。录音和文件转写直接在手机上执行；完成转写后，可连接自己配置的云端语言模型，结合上下文校对文字，再生成一份分点摘要。

## 安装与快速开始

需要Android8.0及以上、arm64-v8a架构的设备，并为APK及首次准备模型预留存储空间。安装包内置语音识别、VAD和说话人模型，首次转写即可在离线状态下使用。

1. 从[GitHub发行页](https://github.com/Roylyl/ASRtestII/releases/latest)下载安装包。版本标签与附件对应，例如`V1.1.0`对应`ASRtestII-1.1.0.apk`。
2. 打开底部“测试”页，点击“开始手机录音”，允许麦克风权限。看到正在录音的提示后开始讲话，点击“结束手机录音”提交尾句。
3. 使用已有录音时，点击“导入WAV/ZIP”，通过系统文件选择器选取单个WAV，或包含多条WAV的ZIP。
4. 等待处理完成。点击结果标题右侧的“展开”，可以滚动阅读、选择文字或复制全文。

默认开启VAD分段、本地声纹区分和连续说话人细分。启用这些功能后，结果按发言时间排列。

```text
[00:01.00–00:03.00]说话人1：周五能完成这项工作吗？
[00:03.20–00:05.10]说话人2：可以，我周四核对结果。
```

录音和文件识别期间请保持应用在前台。首次准备模型及长音频的说话人处理需要等待，页面会显示当前进度和中间结果。

## 主要功能

|功能|可以做什么|
|---|---|
|手机录音识别|采集麦克风音频，在本机完成中英文转写，结束录音时刷新尾句。|
|WAV与ZIP导入|导入单条录音，或解压ZIP中的多条WAV，按文件名顺序处理并显示进度。|
|VAD语音分段|提供Silero、TEN和实验性FSMN选项，检测人声区间并管理停顿。|
|说话人细分|结合pyannote活动切分与CAM++或ERes2NetV2声纹特征，按时间和人物输出发言。|
|个人声纹管理|录制并命名声纹，保存两种模型的特征，支持重命名、补录和删除。|
|领域与自定义热词|按场景选择词包，也可输入中英文术语，分别调节四档权重。|
|转写勘误与总体摘要|使用配置的云端模型校对转写，再按话题整理关键问答、观点和经历。|
|长文本阅读|识别结果、勘误稿和摘要均可展开阅读，并复制全部文字。|
|运行状态与更新|显示应用和整机RAM占用，支持检查正式发行版、下载更新并调用系统安装器。|

底部四个栏目分别为“测试”“热词”“声纹”“设置”。“测试”用于录音、导入和阅读结果；“声纹”集中管理登记记录与归组阈值；模型选择、VAD开关和云端接口位于“设置”。

## 本地处理与模型

本地识别使用以下流程，各模型承担不同的工作。

```text
麦克风或WAV → 本地转换为16kHz单声道 → VAD与说话人活动切分
           → 声纹匹配与归组 → X-ASR转写 → 按时间、人物显示结果
```

|模块|内置模型|作用与设置|
|---|---|---|
|语音转写|X-ASR1920ms，Zipformer2流式Transducer|处理中英文语音。使用CPU推理，模型与词表合计约586.2MiB。|
|语音活动检测|Silero VAD、TEN VAD|支持手机录音和离线文件，在“设置”中切换。|
|语音活动检测|FSMN VAD GGUF|实验选项，仅支持60秒以内的离线文件。|
|说话人活动切分|pyannote/segmentation|判断人物活动区间及重叠，细分没有明显停顿的连续发言。|
|声纹特征|CAM++、ERes2NetV2|提取声音特征，供匿名人物归组和已登记声纹匹配使用。|

默认组合为Silero VAD＋CAM++。模型选择从下一次任务开始生效。“1920ms”表示X-ASR的分块配置，实际文字出现的时间还受设备速度、停顿、切分和处理积压影响。

VAD只判断有没有人声，连续说话人细分进一步处理同一语音区间中的人物变化。匿名编号按本次结果首次出现的顺序从“说话人1”开始；处理中间结果的编号可能调整，完成后显示最终归组。

## 文件导入与结果阅读

支持16位PCM WAV，采样率为16kHz、44.1kHz或48kHz，单声道或双声道。音频在设备本地转换为识别所需的16kHz单声道。

|输入|限制|
|---|---|
|单个WAV|最长20分钟，同时不超过50MiB。|
|ZIP压缩包|压缩包不超过512MiB，最多1000个条目，累计解压不超过1024MiB。|
|ZIP中的单条WAV|遵循单个WAV的时长、格式和大小限制。|

ZIP中的WAV按文件名排序，每条录音独立处理并刷新尾部。界面显示当前文件的处理进度，结果时间轴按可读取WAV的时长顺序累计；停止任务可点击“停止识别”。格式不符合要求的文件会显示错误，应用继续处理后续条目。

长文件先显示当前句的即时预览，再逐步显示分人结果。完成后，结果区保留“[起止时间]说话人：内容”的形式。导入过程会在应用专属目录的`runs`中保存文字与结构化结果。

需要整理摘要时，可以继续使用本次结果；已经有勘误稿时，也能点击“打开已有勘误稿”，选择1MiB以内的UTF-8文本，再直接生成摘要。稿件每行须采用上面的时间区间、说话人和全角冒号格式。

## 热词词包与权重

热词用于提高当前场景中专业词、品牌名或人名在解码中的竞争权重。在“热词”页可多选领域词包，点击查看词条，也可以在顶部管理自定义热词。

内置词包覆盖家居生活、美食餐饮、旅行出行、办公会议、金融、科技、法律、教育和工程等领域。自定义词支持中文、英文字母、数字及中英混合输入，每行填写一个词，保存后单独启用。

|档位|默认|轻度|中度|高|
|---|---|---|---|---|
|词条加分|1|2|3|4|

每个领域词包和自定义热词均可独立调节权重。各词包默认关闭，权重滑块默认处于最低档；保存或切换词包后，从下一次任务生效。

启用有效热词时使用`modified_beam_search`，候选路径数为4；没有有效热词时使用`greedy_search`。词表支持、读音和录音质量都会影响最终效果，权重过高也可能把相近的词带偏，适合按当前话题选择少量相关词包。

## 声纹登记与归组调节

在“声纹→声纹管理”打开已保存的列表，点击右上角“＋”，填写名称并按提示朗读。建议在安静环境自然说话10～20秒，结束后点击“保存声纹”。登记需要至少3秒有效语音，单次最长30秒。

一次登记会分别提取CAM++和ERes2NetV2特征，切换模型后可继续使用同一个名字。列表支持重命名和删除；旧版记录若只包含一种模型，可由原说话人“补录以支持两种模型”。原始登记录音仅暂存在内存，保存的是声纹特征。

“声纹”页还提供滑块、数值输入和加减按钮，用于调整以下参数，最后点击“保存设置”。

|参数|默认值|影响|
|---|---|---|
|已有说话人匹配下限|0.45|声纹达到匹配条件时，可归入已有说话人。|
|新建说话人相似度上限|0.25|相似度低于此值时才允许新建人物，数值越低，新建越严格。|
|多个说话人最小分差|0.08|判断最佳与次佳人物是否有足够差距。|
|说话人切分聚类阈值|0.50|控制说话人活动切分的聚类，与声纹归组参数分别调整。|

已手动保存的设置继续生效，新的选择从下一次任务开始使用。保存时，新建上限须低于已有匹配下限和短片段匹配的固定下限0.40。

姓名匹配属于模型预测。短回答、噪声、声音过轻和多人重叠会影响归属，部分发言会保留未识别或重叠标记；时间区间来自音频处理边界，不能当作逐词时间戳。

## 云端勘误与总体摘要

本地转写完成后，可点击“开始勘误并总结”。首次使用需要在“设置→云端模型”填写三个项目。

|项目|填写内容|
|---|---|
|服务地址|兼容Chat Completions的HTTPS接口地址，可以填写API基础地址或完整的`/chat/completions`路径。|
|API Key|由所用服务提供的密钥，单独填写在密钥栏。|
|模型名称|该接口实际支持的模型ID。|

配置表单预填DeepSeek服务地址`https://api.deepseek.com/v1`和模型ID`deepseek-flash`。填入自己的密钥并保存后即可使用，也可以更换为其他兼容服务。

处理分为勘误和摘要两个阶段，结果分别显示在“转写勘误”和“内容摘要”中。

- 转写勘误结合整稿上下文、逻辑和常识修正有依据的错词，保留每条发言的时间与说话人。
- 内容摘要根据勘误稿生成一份总体摘要，按话题保留关键问答、观点、具体经历，以及明确的结论或待办。

多条录音优先一起提交，只有服务明确报告上下文超限时，才分块提取并汇总为一份摘要。使用内置适配的DeepSeek配置时，勘误和摘要启用深度思考，上下文与输出长度沿用服务默认设置。若只有摘要失败，可以点击“重新生成摘要”，复用已有勘误稿。

## 数据与权限

|内容|处理与保存方式|
|---|---|
|音频、VAD、语音转写与声纹比较|在手机本地处理。|
|声纹登记|特征保存在应用私有目录，登记录音只在内存中处理。|
|云端勘误与摘要|点击开始后，将转写文字和说话人标签发送到配置的服务，音频与声纹特征保留在本机。|
|API Key|由Android Keystore保护的密钥加密，保存在本机。|
|应用更新|联网读取GitHub正式发行信息，并按用户选择下载APK。|

首次录音需要麦克风权限；文件导入使用Android系统文件选择器。更新通知和安装更新可能触发相应的系统授权，按手机提示操作即可。

## 应用内更新

应用启动时检查正式发行版，之后按约30分钟周期检查，也可以在“设置”最底部点击“检查更新”。后台执行时间会受到网络和系统省电策略影响。

发现新版本后，弹窗展示更新信息。下载过程中可查看进度或切换到后台继续；下载完成后由Android系统安装器请求确认。后台下载完成时，可从通知或回到应用继续安装。

## 常见问题

<details>
<summary>为什么刚点击录音还没有文字？</summary>

首次需要准备模型。开启VAD后，应用会等待停顿再提交语音片段；开启说话人细分后，还需要处理人物活动和声纹归组。请根据页面状态判断，看到正在录音的提示后再说话。

</details>

<details>
<summary>为什么锁屏、切换应用后任务结束了？</summary>

当前手机录音和导入任务需要保持前台。处理长音频时，请让应用留在前台并保持手机正常运行。

</details>

<details>
<summary>为什么同一个人出现多个编号，或者一段话显示多人重叠？</summary>

人物归组依赖声纹相似度和切分结果。语气、语言、距离、噪声及重叠发言都会影响判断。可在“声纹”页结合录音调节参数，或登记已知人物的声纹；多人重叠仍可能难以准确归属。

</details>

<details>
<summary>为什么选择WAV后提示格式不支持？</summary>

WAV是容器格式，内部也可能存放浮点PCM或压缩音频。请将文件转换为16位PCM、16kHz/44.1kHz/48kHz、单声道或双声道，并同时满足时长与文件大小限制。

</details>

<details>
<summary>为什么勘误和摘要按钮要求配置模型？</summary>

这两项使用自己配置的云端语言模型。请填写服务地址、API Key和模型ID并保存。普通录音和文件转写可独立使用。

</details>

<details>
<summary>为什么构建时提示模型缺失或仍是Git LFS指针？</summary>

模型权重由Git LFS管理。在仓库根目录运行`git lfs pull`拉取实际文件，再重新构建。

</details>

## 从源码构建

构建环境为Windows、PowerShell7、JDK17、Android SDK和Git LFS。SDK需要包含Android平台与Build Tools；可配置`JAVA_HOME`和`ANDROID_HOME`，也可向构建脚本传入路径。

```powershell
git lfs install
git clone https://github.com/Roylyl/ASRtestII.git
cd ASRtestII
git lfs pull
pwsh -File .\Build-Apk.ps1 -Unsigned
```

当前版本的未签名安装包默认输出到Windows桌面，文件名为`ASRtestII-1.1.0-unsigned.apk`。使用已有Android签名密钥构建可安装的APK时，指定密钥库和别名，按提示输入密码。

```powershell
pwsh -File .\Build-Apk.ps1 -Keystore "D:\keys\release.p12" -KeyAlias "release"
```

默认签名包名为`ASRtestII-版本号.apk`，可用`-OutputApk`指定输出路径。覆盖安装已有版本需要沿用相同证书。

<details>
<summary>项目结构</summary>

|目录或文件|内容|
|---|---|
|`app/src/org/hotwordtest/`|Android界面、音频处理、声纹管理、云端接口和更新逻辑。|
|`app/assets/`|Git LFS模型、词表与随附许可。|
|`app/jni/arm64-v8a/`|Android原生运行库。|
|`native/`|原生模块相关源码。|
|`tools/`|构建工具与签名脚本。|
|`Build-Apk.ps1`|编译、打包与签名入口。|

</details>

<details>
<summary>维护者发布新版本</summary>

1. 更新[AndroidManifest.xml](./app/AndroidManifest.xml)中的`versionName`，同时递增`versionCode`。
2. 用原发行证书构建签名APK，使附件名与版本一致。例如`versionName=1.1.1`时，附件名为`ASRtestII-1.1.1.apk`。
3. 在GitHub创建标签为`V1.1.1`的正式Release，上传APK并填写更新说明。应用按三段版本号比较更新，跳过草稿和预发布版。

</details>

## 上游项目与许可证

ASRtestII集成了以下开源项目。

- [X-ASR](https://github.com/Gilgamesh-J/X-ASR)提供中英文语音识别模型。
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)提供本地推理与语音处理接口。
- [Silero VAD](https://github.com/snakers4/silero-vad)与[TEN VAD](https://github.com/TEN-framework/ten-vad)提供语音活动检测模型。
- [CAM++](https://modelscope.cn/models/iic/speech_campplus_sv_zh_en_16k-common_advanced)、[ERes2NetV2](https://modelscope.cn/models/iic/speech_eres2netv2_sv_zh-cn_16k-common)和[pyannote/segmentation](https://huggingface.co/pyannote/segmentation-3.0)分别用于声纹和说话人切分。

<a id="许可证与第三方声明"></a>

原创代码和原创文档采用[Apache License2.0](./LICENSE)。模型、词表、预编译库和构建工具保留各自许可。TEN VAD附有Agora部署限制；当前随APK分发的sherpa-onnx JNI包含GPL许可的eSpeak NG组件。再分发时请阅读[第三方声明与源码索引](./THIRD_PARTY_NOTICES.md)，其中列出了随附许可、组件来源和对应源码事项。
