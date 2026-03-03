# Fix TorrentNotifier package
$file = "D:\Android_Projects\Media-DL\app\src\main\java\com\bismaya\mediadl\ui\TorrentNotifier.java"
$text = Get-Content $file -Raw
$new  = $text `
    -replace 'package org\.proninyaroslav\.libretorrent\.ui', 'package com.bismaya.mediadl.ui' `
    -replace 'import org\.proninyaroslav\.libretorrent', 'import com.bismaya.mediadl' `
    -replace 'org\.proninyaroslav\.libretorrent\.FOREGROUND', 'com.bismaya.mediadl.FOREGROUND' `
    -replace 'org\.proninyaroslav\.libretorrent\.DEFAULT_NOTIFY', 'com.bismaya.mediadl.DEFAULT_NOTIFY' `
    -replace 'org\.proninyaroslav\.libretorrent\.FINISH_NOTIFY', 'com.bismaya.mediadl.FINISH_NOTIFY'
Set-Content $file $new -NoNewline
Write-Output "TorrentNotifier fixed."

# Find all R resource references across service and the TorrentNotifier
$files = @(
    "D:\Android_Projects\Media-DL\app\src\main\java\com\bismaya\mediadl\service\TorrentService.java",
    "D:\Android_Projects\Media-DL\app\src\main\java\com\bismaya\mediadl\ui\TorrentNotifier.java"
)
Write-Output "`n=== R resource references ==="
Select-String -Path $files -Pattern "R\.(string|drawable|color|layout|id|dimen|attr)\.\w+" | 
    Select-Object -ExpandProperty Line | 
    ForEach-Object { [regex]::Matches($_, 'R\.(string|drawable|color|layout|id|dimen|attr)\.\w+') } | 
    ForEach-Object { $_.Value } |
    Sort-Object -Unique
