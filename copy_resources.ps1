$srcRes = "D:\Android_Projects\Media-DL\reference\libretorrent\app\src\main\res"
$dstRes = "D:\Android_Projects\Media-DL\app\src\main\res"

# 1. Copy vector drawables needed by TorrentNotifier/TorrentService
$drawables = @(
    "ic_check_24px.xml",
    "ic_drive_file_move_24px.xml",
    "ic_error_24px.xml",
    "ic_info_24px.xml",
    "ic_pause_24px.xml",
    "ic_play_arrow_24px.xml",
    "ic_power_settings_new_24px.xml"
)
foreach ($d in $drawables) {
    Copy-Item "$srcRes\drawable\$d" "$dstRes\drawable\$d" -Force
}
Write-Output "Copied $($drawables.Count) vector drawables."

# 2. Copy ic_app_notification from all density folders
$densities = @("drawable-hdpi","drawable-mdpi","drawable-xhdpi","drawable-xxhdpi","drawable-xxxhdpi")
foreach ($density in $densities) {
    $src = "$srcRes\$density\ic_app_notification.png"
    if (Test-Path $src) {
        $dstDir = "$dstRes\$density"
        if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir | Out-Null }
        Copy-Item $src "$dstDir\ic_app_notification.png" -Force
    }
}
Write-Output "Copied ic_app_notification density variants."

Write-Output "=== Done copying resources ==="
