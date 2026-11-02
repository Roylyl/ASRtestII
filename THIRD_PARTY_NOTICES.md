# ASRtestII第三方声明

ASRtestII原创应用代码与原创文档采用[Apache License 2.0](./LICENSE)。仓库包含的第三方源码、模型权重、词表、预编译库和构建工具，按各自的上游条款分发。对应的著作权、署名和许可声明归各权利人所有。

## 模型和词表

|随附组件|用途|上游许可|
|---|---|---|
|X-ASR-zh-en、词表及BPE词元表|中英文流式识别和热词解码|Apache-2.0。[模型卡](https://huggingface.co/GilgameshWind/X-ASR-zh-en) · [项目许可证](https://github.com/Gilgamesh-J/X-ASR/blob/main/LICENSE)|
|pyannote/segmentation-3.0|说话人切分|MIT，Copyright (c) 2023 CNRS。上游模型仓库另有下载访问条件。[原始许可证](https://huggingface.co/pyannote/segmentation-3.0/blob/main/LICENSE)|
|CAM++和ERes2NetV2|提取声纹特征|两份模型卡均标注Apache-2.0。[CAM++模型卡](https://modelscope.cn/models/iic/speech_campplus_sv_zh_en_16k-common_advanced) · [ERes2NetV2模型卡](https://modelscope.cn/models/iic/speech_eres2netv2_sv_zh-cn_16k-common)|
|Silero VAD|语音活动检测|MIT。[上游许可证](https://github.com/snakers4/silero-vad/blob/v4.0/LICENSE)|
|TEN VAD|语音活动检测|采用含Agora附加条件的许可，涉及与Agora业务竞争的部署、直接终端用户和衍生作品；另含BSD声明。使用、修改或再分发该组件前应阅读完整条款。[上游许可](https://github.com/TEN-framework/ten-vad/blob/main/LICENSE) · [随附声明](./app/assets/licenses/TEN-VAD-NOTICES.txt)|
|FSMN-VAD GGUF|离线语音活动检测|模型卡标注Apache-2.0，前端和运行库另有许可。[固定修订模型卡](https://huggingface.co/FunAudioLLM/fsmn-vad-GGUF/blob/6840bae4c5c92ee8c04faaf4db23dd0105098d7f/README.md) · [前端与运行库说明](./app/assets/fsmn/NOTICE.txt)|

具体文件名、转换来源、权利人及许可副本见[模型与词表清单](./app/assets/licenses/MODEL-SOURCES.txt)。

## 随APK分发的软件

- sherpa-onnx自身采用Apache-2.0。当前预编译的`libsherpa-onnx-jni.so`也包含启用TTS时链接的eSpeak NG组件，其许可为GPL-3.0-or-later。ASRtestII界面只使用识别功能，但发布APK仍然分发了这部分二进制。提供该JNI的下载时，需要按适用的GPL条款提供对应源码和构建所需材料；复制许可证正文不能替代对应源码。[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) · [运行库声明与来源](./app/assets/licenses/software/NOTICE.txt)
- ONNX Runtime、Kotlin标准库、JetBrains注解、Android NDK C++运行库及其所含组件各有许可和署名。FSMN前端和ggml的许可另随其文件保留。[运行库许可目录](./app/assets/licenses/software/) · [FSMN声明](./app/assets/fsmn/NOTICE.txt)

公开发行当前JNI前，需要核实它的完整对应源码及构建材料确实可由接收者取得，并在发行附件旁标明获取方式。运行库声明已列出已定位的sherpa-onnx、Piper和eSpeak NG修订及构建入口，目前没有足够记录确认它们覆盖当前预编译JNI的全部构建输入。

## 仅随源码仓库分发的工具

Windows构建使用的Kotlin编译器及其依赖位于`tools/kotlin`。这些JAR的许可和再分发资料见[构建工具声明](./third_party/licenses/NOTICE.txt)。其中Trove4j有独立的LGPL条款；构建工具的使用范围和APK内的运行时组件分开列明。

重新构建的APK在`assets/licenses/`中附带项目许可证、本声明、模型许可和运行库声明；FSMN的随附声明位于`assets/fsmn/`。本页相对链接以源码仓库根目录为基准。
