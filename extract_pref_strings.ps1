$srcStrings = "D:\Android_Projects\Media-DL\reference\libretorrent\app\src\main\res\values\strings.xml"
$xml = [xml](Get-Content $srcStrings)

$prefKeys = @(
    'pref_key_cpu_do_not_sleep',
    'pref_key_foreground_notify_combined_pause_button',
    'pref_key_foreground_notify_sorting',
    'pref_key_foreground_notify_status_filter'
)
foreach ($name in $prefKeys) {
    $node = $xml.resources.string | Where-Object { $_.name -eq $name }
    if ($node) {
        Write-Output "    <string name=`"$($node.name)`">$($node.'#text')</string>"
    } else {
        Write-Output "    <!-- NOT FOUND: $name -->"
    }
}
