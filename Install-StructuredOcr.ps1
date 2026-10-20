param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$bidVenv = Join-Path $PSScriptRoot '.local/table-runtime'
$bidPython = Join-Path $bidVenv 'Scripts/python.exe'
if (-not (Test-Path -LiteralPath $bidPython)) {
    & $Python -m venv $bidVenv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated OCR runtime.' }
}
& $bidPython -m pip install --disable-pip-version-check -r (Join-Path $PSScriptRoot 'scripts/structured_ocr/requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'OCR runtime installation failed.' }
& $bidPython -m unittest discover -s (Join-Path $PSScriptRoot 'scripts/structured_ocr') -p 'test_*.py'
if ($LASTEXITCODE -ne 0) { throw 'OCR runtime checks failed.' }
Write-Host ('Structured OCR runtime ready: ' + $bidPython)
Write-Host 'Set BIDCHECK_OCR_ENABLED=true, provide BIDCHECK_OCR_KEY (or BIDCHECK_MODEL_KEY), set BIDCHECK_OCR_LAYOUT_ENABLED=true, BIDCHECK_OCR_LAYOUT_ENGINE=native-grid and BIDCHECK_OCR_LAYOUT_PYTHON to the path above, then restart BidCheck.'
