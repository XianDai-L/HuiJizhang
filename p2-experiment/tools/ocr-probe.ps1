# Probe: how deepseek-ai/DeepSeek-OCR wants to be called on SiliconFlow.
#
# Why: it is an OCR-specific model (8K context, "Free OCR." style prompt template),
# not a general vision chat model, so the OpenAI-compatible shape has to be checked
# before wiring it into the B route as the real transcription step.
#
# ASCII-only on purpose: PowerShell 5.1 reads .ps1 as ANSI, non-ASCII literals would corrupt.

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$key = [regex]::Match(
    (Get-Content 'D:\HuiJi\WiseBook\local.properties' -Raw),
    'wisebook\.siliconflow\.key=([^\r\n]+)').Groups[1].Value.Trim()
if ([string]::IsNullOrEmpty($key)) { Write-Output 'NO SILICONFLOW KEY'; exit 1 }

$image = if ($args.Count -gt 0) { $args[0] } else { 'D:\HuiJi\p2-experiment\samples\s2.jpg' }
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($image))

$prompt = if ($args.Count -gt 1) { $args[1] } else { '<image>\nFree OCR.' }

$body = '{"model":"deepseek-ai/DeepSeek-OCR","temperature":0,"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,' + $b64 + '"}},{"type":"text","text":"' + $prompt + '"}]}]}'

try {
    $r = Invoke-RestMethod -Uri 'https://api.siliconflow.cn/v1/chat/completions' `
        -Headers @{ Authorization = "Bearer $key" } -Method Post `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json'
    Write-Output ('--- usage: ' + ($r.usage | ConvertTo-Json -Compress))
    Write-Output '--- content:'
    Write-Output $r.choices[0].message.content
} catch {
    $msg = $_.ErrorDetails.Message
    if (-not $msg) { $msg = $_.Exception.Message }
    Write-Output ('FAIL | ' + $msg)
}
