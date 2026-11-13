#!/system/bin/sh
# No files are overlaid, so removing the module + rebooting loads the stock
# driver from its partition again. Just clean our state dir.
rm -rf /data/adb/malachite_wifi_monitor 2>/dev/null
