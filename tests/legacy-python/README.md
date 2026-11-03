# 历史 Python 实现与回归参考

这里保存迁移前的 Agent 和本地 OCR 实现及其测试，用于协议、来源校验和算法行为对照。生产代码不加载这些文件，Maven 不将它们打入应用 JAR，启动应用也不需要 Python、pip 或 Node。

- `agent-worker/`：原 Python Agents SDK 实现、离线单元测试和独立供应商探针。
- `structured_ocr/`：原 Python 网格和 OCR 实现及离线测试。

子目录内的 README 记录旧版本的安装和协议，仅供历史复现；当前运行方式以项目根目录 README 和 Java 运行说明为准。独立供应商探针只有显式执行时才可发送请求，普通构建和测试不执行它。

如需运行这些历史回归，在专用测试环境安装对应 `requirements`，然后执行：

```powershell
python tests/legacy-python/agent-worker/run_tests.py
python -m unittest discover -s tests/legacy-python/structured_ocr -p 'test_*.py'
```
