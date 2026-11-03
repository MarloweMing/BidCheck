param([string]$ModelDirectory = '.local/table-models', [switch]$VerifyOnly)
$ErrorActionPreference = 'Stop'
$bidModelDirectory = if ([IO.Path]::IsPathRooted($ModelDirectory)) { [IO.Path]::GetFullPath($ModelDirectory) } else { [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $ModelDirectory)) }
if (-not (Test-Path -LiteralPath $bidModelDirectory -PathType Container)) {
    if ($VerifyOnly) { throw 'Local OCR model directory is missing.' }
    New-Item -ItemType Directory -Path $bidModelDirectory -Force | Out-Null
}
# Fixed RapidAI release and its published model hashes; no interpreter or package installer.
$bidModelBase = 'https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/'
$bidModels = @(
    @{ Name = 'PP-OCRv6_det_small.onnx'; Source = 'PP-OCRv6/det/PP-OCRv6_det_small.onnx'; Sha256 = '090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f' },
    @{ Name = 'PP-OCRv6_rec_small.onnx'; Source = 'PP-OCRv6/rec/PP-OCRv6_rec_small.onnx'; Sha256 = '6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884' },
    @{ Name = 'ch_ppocr_mobile_v2.0_cls_mobile.onnx'; Source = 'PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx'; Sha256 = 'e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c' }
)
foreach ($bidModel in $bidModels) {
    $bidModelPath = Join-Path $bidModelDirectory $bidModel.Name
    if ((Test-Path -LiteralPath $bidModelPath -PathType Leaf) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $bidModelPath).Hash -eq $bidModel.Sha256) {
        Write-Host ('Verified: ' + $bidModel.Name)
        continue
    }
    if ($VerifyOnly) { throw ('Local OCR model is missing or does not match the release hash: ' + $bidModel.Name) }
    $bidTemporaryModel = Join-Path $bidModelDirectory ('.download-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        Invoke-WebRequest -UseBasicParsing -Uri ($bidModelBase + $bidModel.Source) -OutFile $bidTemporaryModel -TimeoutSec 300
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $bidTemporaryModel).Hash -ne $bidModel.Sha256) { throw ('Downloaded OCR model failed hash verification: ' + $bidModel.Name) }
        Move-Item -LiteralPath $bidTemporaryModel -Destination $bidModelPath -Force
        Write-Host ('Installed: ' + $bidModel.Name)
    } finally {
        if (Test-Path -LiteralPath $bidTemporaryModel) { Remove-Item -LiteralPath $bidTemporaryModel -Force }
    }
}
Write-Host ('Java local OCR models ready: ' + $bidModelDirectory)
Write-Host 'Set BIDCHECK_OCR_LAYOUT_ENABLED=true and BIDCHECK_OCR_LAYOUT_ENGINE=native-grid. Set BIDCHECK_OCR_LAYOUT_MODEL_DIRECTORY when using a non-default model directory.'
