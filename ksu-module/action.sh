#!/system/bin/sh
# Action button: launch the Stryker malachite fork.
PKG=com.zalexdev.stryker.devicefork
am start -n "$PKG/com.zalexdev.stryker.MainActivity" >/dev/null 2>&1 \
  || monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
echo "Launched Stryker malachite."
echo "Driver log: /data/adb/malachite_wifi_monitor/swap.log"
echo "(Manual monitor: su, then . \$MODDIR/common.sh; arm_monitor 6 / disarm_monitor)"
