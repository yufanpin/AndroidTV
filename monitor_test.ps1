$adb = "C:\Users\yu\Desktop\platform-tools\adb.exe"
$logFile = "C:\Users\yu\Desktop\tivimate变体\stability_test_log.txt"

# Header
$startTime = Get-Date
"Stability Test Started: $startTime" | Out-File $logFile
"" | Out-File $logFile -Append

$currentPid = $null
$lastLogTime = 0

for ($i = 0; $i -lt 360; $i++) {
    Start-Sleep -Seconds 5
    
    # ADB keepalive
    $null = & $adb shell "echo keepalive > /dev/null" 2>&1
    
    # Every 30 seconds (6 iterations), log status
    if ($i % 6 -eq 0) {
        $now = Get-Date -Format "HH:mm:ss"
        $checkPid = & $adb shell pidof com.tivimatelite 2>&1
        $state = & $adb shell dumpsys media_session 2>&1 | Select-String "com.tivimatelite"
        
        if ($checkPid -and $checkPid -ne $currentPid) {
            "  *** PID CHANGED: $currentPid -> $checkPid at $now ***" | Out-File $logFile -Append
            $currentPid = $checkPid
        } elseif (-not $checkPid) {
            "  *** APP NOT RUNNING at $now ***" | Out-File $logFile -Append
            $currentPid = $null
        } elseif ($currentPid -eq $null) {
            "  PID=$checkPid at $now" | Out-File $logFile -Append
            $currentPid = $checkPid
        } else {
            "  PID=$checkPid OK at $now" | Out-File $logFile -Append
        }
    }
}

$endTime = Get-Date
"`nStability Test Ended: $endTime" | Out-File $logFile -Append
"Duration: $((($endTime - $startTime).TotalMinutes).ToString('0.0')) min" | Out-File $logFile -Append

# Dump file log
"`n=== tivimate_diag.txt ===" | Out-File $logFile -Append
& $adb shell cat /data/data/com.tivimatelite/cache/tivimate_diag.txt 2>&1 | Out-File $logFile -Append

Write-Output "Test complete. Log: stability_test_log.txt"
