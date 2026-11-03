# 本地文字框与表格网格组件

本组件只输出图像来源事实，不作业务判断。默认模式使用 RapidOCR 的检测框、识别文字和置信度；`gridOnly` 模式不初始化或调用 OCR，只分析本地图像中实际存在的深色横竖线。

## 启动与协议

使用专用 Python 环境运行 `worker.py --model-dir <模型缓存目录> --timeout-seconds 120`。一行请求对应一行响应，UTF-8 JSON；保持进程和标准输入打开即可复用已加载模型。标准输出专用于协议，依赖库消息写标准错误。Java 调用端仍必须限制请求并发、读取量和总超时，并在超时或进程异常后终止进程。

请求：

```json
{"id":"request-1","imageBase64":"原始PNG或JPEG的base64","gridOnly":true}
```

`gridOnly` 可省略，默认 `false`。不支持文件路径、URL或任意脚本请求。单图不超过 6 MiB、1200 万像素；不接受动画、多帧或其他格式。按存储光栅方向解码，不隐式应用 EXIF 旋转。

正常响应包含：

- `id`、`schemaVersion:local-ocr-grid-1`、`policy:rapidocr-raster-grid-2`、`status:OK`；
- `width/height/imageHash`：实际解码尺寸和原始图像字节 SHA-256；
- `coordinateSystem:PIXEL_TOP_LEFT`；
- `textBoxes`：本地生成的 `id`、`frameId:f1`、原样 `text`、四点 `polygon`、轴对齐 `bbox:{x,y,width,height}`、`confidence`；
- `readingOrder`：OCR 返回的框编号顺序，不声明语义阅读顺序绝对正确；
- `gridCandidates`：与 Java `GridLayoutAssembler.Grid` 对齐的 `{frameId,coordinateSpace:PIXEL_XYXY,rows,columns,cells}`；每个单元格只含 `row/column/rowSpan/columnSpan/box:[x1,y1,x2,y2]/header:false`，不含自编文本或引用；
- `engine`、`gridOnly`、`warnings`、`elapsedMs`。

`OK` 只表示处理执行成功。无网格时返回空数组与 `NO_CLOSED_GRID`；无法确定闭合边界时拒绝该网格并返回 `GRID_GEOMETRY_AMBIGUOUS`。网格本身不证明它是业务表格，不证明表头身份或主体归属。调用方必须将框与同源转录对齐，再由 Java 进行几何唯一包含、行列完整覆盖、合并格和原文引用校验。不能用识别结果替换旧版本转录以强行通过校验。

错误响应仅含安全固定码，不带文档内容、异常正文、路径或密钥。达到超时时进程退出码为 `124`，调用方应标记技术失败并保留转录。超过一行请求预算则返回 `REQUEST_LIMIT` 后退出，避免把超长请求的尾部当成新请求。

## 指定单元格重新识别

请求可增加 `cellRegions:[{id,box:[x1,y1,x2,y2]}]`，同时保持 `gridOnly:false`。最多 64 个互不重叠区域，必须在当前图像边界内；ID 唯一且仅支持字母、数字、下划线、短横线。该模式只识别指定区域，不额外识别整页，也不按文字长度拆分已有识别内容。

区域按左上向上取整、右下向下取整进行内向裁切。放大最多 4 倍，输入识别器的每幅裁图长边不超过 2400、面积不超过 400 万像素。所有文字框经实际裁图/缩放映回原图坐标，`sourceRegion:{id,box,scale,scaleX,scaleY,inputWidth,inputHeight}` 记录实际整数裁图区域与两个轴的实际倍率；`scaleX/scaleY` 反映缩放尺寸取整。未识别出文字会留下 `CELL_OCR_NO_TEXT` 提示，不能把空结果当成不存在内容。

裁格输出仍须与当前同源原转录精确匹配，不能凭新识别文字修改原转录、补全证号或推断坐标。整个请求预算是 6 MiB 图像的 Base64 加 32 KiB 区域元数据。

## 资源和边界

ONNX Runtime 使用 CPU，单请求顺序处理，运算线程 2、调度线程 1；OpenCV 线程 2。默认 OCR 模型跟随固定 `rapidocr==3.9.2` 的 PP-OCRv6 small 配置，首次使用会从该包配置的官方模型源下载到独立缓存。运行期依赖由项目部署方安装，本脚本不会运行安装命令。

当前像素检测面向近似水平/垂直、浅色背景上的深色闭合表格线。无边框表、严重倾斜、断裂线、非常粗线不承诺重建；缺边有歧义时不猜测。所有 `header` 均为 `false`，首行不自动推断为表头。

官方来源：[RapidOCR 输出和用法](https://rapidai.github.io/RapidOCRDocs/main/install_usage/rapidocr/usage/)、[RapidOCR 原图坐标映射实现](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/main.py)。

## 离线验证

`python -m unittest discover -s scripts/structured_ocr -p "test_*.py" -v`

回归不下载模型、不发云请求；覆盖图像预算、协议恢复、原值/坐标/置信度、浅色表头线、闭合网格、合并格、断边拒绝和 `gridOnly` 不调用 OCR。
