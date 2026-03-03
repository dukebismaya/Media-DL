$src = "D:\Android_Projects\Media-DL\reference\libretorrent\app\src\main\java\org\proninyaroslav\libretorrent"
$dst = "D:\Android_Projects\Media-DL\app\src\main\java\com\bismaya\mediadl"

# 1. Copy core/, service/, receiver/
robocopy "$src\core"     "$dst\core"     /E /NFL /NDL /NJH /NJS
robocopy "$src\service"  "$dst\service"  /E /NFL /NDL /NJH /NJS
robocopy "$src\receiver" "$dst\receiver" /E /NFL /NDL /NJH /NJS

# 2. Copy nanohttpd (streaming lib) keeping its own package
$nanoDst = "D:\Android_Projects\Media-DL\app\src\main\java\org\nanohttpd"
robocopy "D:\Android_Projects\Media-DL\reference\libretorrent\app\src\main\java\org\nanohttpd" $nanoDst /E /NFL /NDL /NJH /NJS

# 3. Copy ernieyu feedparser keeping its own package
$feedDst = "D:\Android_Projects\Media-DL\app\src\main\java\com\ernieyu"
robocopy "D:\Android_Projects\Media-DL\reference\libretorrent\app\src\main\java\com\ernieyu" $feedDst /E /NFL /NDL /NJH /NJS

Write-Output "=== Copy complete. File counts ==="
Write-Output "core:     $((Get-ChildItem `"$dst\core`"    -Recurse -File).Count)"
Write-Output "service:  $((Get-ChildItem `"$dst\service`" -Recurse -File).Count)"
Write-Output "receiver: $((Get-ChildItem `"$dst\receiver`" -Recurse -File).Count)"

# 4. Bulk rename packages in all copied Java files under mediadl/
Write-Output "=== Renaming packages ==="
$files = Get-ChildItem "$dst\core", "$dst\service", "$dst\receiver" -Recurse -Filter "*.java"
$count = 0
foreach ($file in $files) {
    $text = Get-Content $file.FullName -Raw
    $new  = $text `
        -replace 'package org\.proninyaroslav\.libretorrent', 'package com.bismaya.mediadl' `
        -replace 'import org\.proninyaroslav\.libretorrent', 'import com.bismaya.mediadl' `
        -replace 'BuildConfig\.SESSION_LOGGING', 'false'
    if ($new -ne $text) {
        Set-Content $file.FullName $new -NoNewline
        $count++
    }
}
Write-Output "Renamed packages in $count files."
Write-Output "=== Done ==="
