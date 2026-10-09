# Ресурсы, которые вшиваются в установщик Лоли для Windows (desktop/resources/common):
#  - vosk-model-ru — распознавание русской речи без интернета;
#  - loli-voice/<id> — встроенный русский «Голос Лоли» (Piper VITS для sherpa-onnx), тот же, что на телефоне.
# Голос берётся из служебного релиза voices-v1 этого репозитория и проверяется по sha256.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$common = "desktop/resources/common"
New-Item -ItemType Directory -Force $common | Out-Null

if (-not (Test-Path "$common/vosk-model-ru/am/final.mdl")) {
  $zip = "$env:RUNNER_TEMP\vosk.zip"
  Invoke-WebRequest "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" -OutFile $zip
  Expand-Archive $zip -DestinationPath "$env:RUNNER_TEMP\vosk" -Force
  Move-Item "$env:RUNNER_TEMP\vosk\vosk-model-small-ru-0.22" "$common/vosk-model-ru"
}

$voice = "denis"
$sha = "8bcfc5cea11b0d943d03f6b4d4da0eacf8b5ec2af5c35ff021a03c65b16c2138"
$target = "$common/loli-voice/$voice"
if (-not (Test-Path "$target/vits-piper-ru_RU-$voice-medium/tokens.txt")) {
  $zip = "$env:RUNNER_TEMP\voice-$voice.zip"
  Invoke-WebRequest "https://github.com/Lucky2356/loli_ai/releases/download/voices-v1/loli-voice-ru-$voice.zip" -OutFile $zip
  $actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
  if ($actual -ne $sha) { throw "Голос $voice повреждён: $actual" }
  New-Item -ItemType Directory -Force $target | Out-Null
  Expand-Archive $zip -DestinationPath $target -Force
}
Get-ChildItem -Recurse "$target" -Depth 1 | Select-Object -First 10 | ForEach-Object { $_.FullName }
