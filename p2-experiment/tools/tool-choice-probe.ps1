# Probe: which tool_choice / thinking combinations the current models accept.
#
# Why: the product client (OpenAiCompatibleLlmClient) always sends
#   "tool_choice": {"type":"function","function":{"name":"..."}}
# and DeepSeek V4 rejects it with
#   400 Thinking mode does not support this tool_choice
# so P2 needs to know the exact accepted shape before touching product code.
#
# This script is ASCII-only on purpose: PowerShell 5.1 reads .ps1 as ANSI,
# so non-ASCII literals here would corrupt on load.

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$key = [regex]::Match(
    (Get-Content 'D:\HuiJi\WiseBook\local.properties' -Raw),
    'wisebook\.deepseek\.key=([^\r\n]+)').Groups[1].Value.Trim()
if ([string]::IsNullOrEmpty($key)) { Write-Output 'NO KEY'; exit 1 }

$uri = 'https://api.deepseek.com/v1/chat/completions'
$headers = @{ Authorization = "Bearer $key" }

$tools = '"tools":[{"type":"function","function":{"name":"create_draft","description":"struct","parameters":{"type":"object","properties":{"direction":{"type":"string"}},"required":["direction"]}}}]'
$msg = '"messages":[{"role":"user","content":"book a taxi 28"}]'

$cases = @(
    @{ n = 'chat   + named   (product shape)'; b = '{"model":"deepseek-chat",' + $msg + ',' + $tools + ',"tool_choice":{"type":"function","function":{"name":"create_draft"}}}' },
    @{ n = 'chat   + required';                 b = '{"model":"deepseek-chat",' + $msg + ',' + $tools + ',"tool_choice":"required"}' },
    @{ n = 'v4-pro + named';                    b = '{"model":"deepseek-v4-pro",' + $msg + ',' + $tools + ',"tool_choice":{"type":"function","function":{"name":"create_draft"}}}' },
    @{ n = 'flash  + named';                    b = '{"model":"deepseek-flash",' + $msg + ',' + $tools + ',"tool_choice":{"type":"function","function":{"name":"create_draft"}}}' },
    @{ n = 'flash  + named + thinking=disabled'; b = '{"model":"deepseek-flash",' + $msg + ',' + $tools + ',"thinking":{"type":"disabled"},"tool_choice":{"type":"function","function":{"name":"create_draft"}}}' },
    @{ n = 'flash  + named + effort=low';       b = '{"model":"deepseek-flash",' + $msg + ',' + $tools + ',"reasoning_effort":"low","tool_choice":{"type":"function","function":{"name":"create_draft"}}}' },
    @{ n = 'flash  + required';                 b = '{"model":"deepseek-flash",' + $msg + ',' + $tools + ',"tool_choice":"required"}' },
    @{ n = 'flash  + auto (no tool_choice)';    b = '{"model":"deepseek-flash",' + $msg + ',' + $tools + '}' },
    @{ n = 'flash  + named + thinking(enabled)'; b = '{"model":"deepseek-flash",' + $msg + ',' + $tools + ',"thinking":{"type":"enabled"},"tool_choice":{"type":"function","function":{"name":"create_draft"}}}' }
)

foreach ($c in $cases) {
    $body = [System.Text.Encoding]::UTF8.GetBytes($c.b)
    try {
        $r = Invoke-RestMethod -Uri $uri -Method Post -Headers $headers -Body $body -ContentType 'application/json'
        $tc = $r.choices[0].message.tool_calls
        $shape = if ($tc) { 'tool_calls=' + $tc[0].function.name } else { 'tool_calls=none' }
        Write-Output ('OK   | ' + $c.n + ' | ' + $shape)
    } catch {
        $msgText = $_.ErrorDetails.Message
        if (-not $msgText) { $msgText = $_.Exception.Message }
        if ($msgText.Length -gt 220) { $msgText = $msgText.Substring(0, 220) }
        Write-Output ('FAIL | ' + $c.n + ' | ' + $msgText)
    }
}
