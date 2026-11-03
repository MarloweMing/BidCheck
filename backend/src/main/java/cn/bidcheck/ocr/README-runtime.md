# Java 结构化 OCR 运行说明

生产链路使用 Java 8：远端 OCR 的 HTTP 调用、原图来源校验、像素网格检测、原文与单元格绑定、本地单元格补字均在应用 JVM 内完成。没有 Python、OpenCV 进程或命令行 OCR 依赖。

## 网格与来源

`PixelGridClient` 使用 JDK 图像解码和 Java 像素算法寻找真实的深色水平、垂直线及闭合网格，保留合并单元格。没有可靠闭合边界时留下提示，不猜测缺失的表格线，不将首行自动当作表头。

坐标始终是实际解码原图的像素坐标；图像字节哈希、宽高、裁切范围和缩放比例必须匹配。网格候选不能自行生成文本、来源引用或业务结论。`GridLayoutAssembler` 和 `LocalOcrGapFiller` 继续验证几何覆盖、文字归属和同源原文；补字不能改写旧转录来强行匹配证据。

## 本地补字

仅对实际检测出的跨格或缺字单元格执行局部识别，最多 64 个区域。Java ONNX Runtime 使用固定 PP-OCRv6 small 检测与识别模型，以及文字方向分类模型；CPU 推理库随应用依赖打包，模型单独存放，不在生产启动时下载或安装软件。

```powershell
./Install-StructuredOcr.ps1
./Install-StructuredOcr.ps1 -VerifyOnly
$env:BIDCHECK_OCR_LAYOUT_MODEL_DIRECTORY = './.local/table-models'
```

安装脚本仅下载固定 RapidAI `v3.9.2` 模型并逐一校验 SHA-256。离线部署可将同样三个文件复制到模型目录：

- `PP-OCRv6_det_small.onnx`
- `PP-OCRv6_rec_small.onnx`
- `ch_ppocr_mobile_v2.0_cls_mobile.onnx`

网格检测不需要模型；补字遇到模型缺失、无文字、超时或无法验证的来源时保留原文字框和明确诊断。结构化通道仍使用原先配置的远端 OCR 取得原文与文字位置，本地补字不增加云端请求。

## 部署与验证

使用 JDK 8 构建运行。ONNX Runtime Java 通过其 JAR 中的 CPU 原生库推理，不需要 Python 环境；部署目标的操作系统和架构须支持该推理库。前端仅是同一应用提供的静态网页。

Java 测试覆盖像素网格、合并格、断边拒绝、坐标与来源校验、资源上限、中断和补字映射；真实模型识别使用本机固定模型及合成图像验证。历史 Python 实现只保留在 `tests/legacy-python/structured_ocr/`，用于算法对照，不进入生产 JAR。
