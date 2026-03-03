$srcStrings = "D:\Android_Projects\Media-DL\reference\libretorrent\app\src\main\res\values\strings.xml"
$content = Get-Content $srcStrings -Raw

# Extract the needed string names
$needed = @(
    'app_running_in_the_background',
    'def',
    'downloading_torrent_notify_template',
    'error',
    'error_template',
    'exact_alarm_permission_warning',
    'finished',
    'foreground_notification',
    'foreground_notify_template',
    'nat_error_title',
    'network_offline',
    'network_online',
    'notify_shutting_down',
    'other_torrent_notify_template',
    'pause_all',
    'permission_denied',
    'pref_key_cpu_do_not_sleep',
    'pref_key_foreground_notify_combined_pause_button',
    'pref_key_foreground_notify_sorting',
    'pref_key_foreground_notify_status_filter',
    'resume_all',
    'seeding_torrent_notify_template',
    'session_error_title',
    'shutdown',
    'single_downloading_torrent_notify_template',
    'torrent_count_notify_template',
    'torrent_error_notify_title',
    'torrent_finished_notify',
    'torrent_moving_content',
    'torrent_moving_title',
    'torrent_status_checking',
    'torrent_status_downloading_metadata',
    'torrent_status_paused',
    'torrent_status_seeding',
    'torrent_status_stopped',
    'torrent_status_downloading',
    'torrent_status_finished',
    'torrent_status_unknown',
    'torrent_status_seeding',
    'torrent_count_notify_template',
    'settings_category_download',
    'settings_category_general',
    'settings_category_interface',
    'settings_category_network',
    'settings_category_scheduler',
    'settings_category_appearance',
    'open_key_details_torrent',
    'notification_channel_downloading',
    'notification_channel_seeding',
    'notification_channel_error'
)

$xml = [xml](Get-Content $srcStrings)
$extracted = @()
foreach ($name in ($needed | Sort-Object -Unique)) {
    $node = $xml.resources.string | Where-Object { $_.name -eq $name }
    if ($node) {
        $extracted += "    <string name=`"$($node.name)`">$($node.'#text')</string>"
    }
}

Write-Output "Extracted $($extracted.Count) strings:"
$extracted | ForEach-Object { Write-Output $_ }
