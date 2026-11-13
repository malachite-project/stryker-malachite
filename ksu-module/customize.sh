SKIPUNZIP=0

ui_print " "
ui_print "  malachite: Stryker + Wi-Fi monitor (TEST)"
ui_print "  driver early-load + Stryker fork (built-in tools)"
ui_print " "

# getprop is spoofed on this ROM; read build.prop / bootconfig directly.
PLATFORM="$(grep -m1 'ro.board.platform=' /vendor/build.prop 2>/dev/null | cut -d= -f2)"
[ -z "$PLATFORM" ] && PLATFORM="$(grep -m1 -o 'androidboot.board_id=[^ ]*' /proc/bootconfig 2>/dev/null)"
[ "$PLATFORM" = "mt6878" ] || abort "! Not an MT6878 (malachite) device (platform='$PLATFORM')"
ui_print "- MT6878 confirmed"
case "$(getprop ro.product.cpu.abi)" in arm64*) ui_print "- arm64 OK" ;; *) abort "! arm64 required" ;; esac

# Driver payload must be complete AArch64 ELFs.
for m in cfg80211 mac80211 wlan_drv_gen4m_6878; do
  KO="$MODPATH/modules/$m.ko"
  [ -s "$KO" ] || abort "! missing modules/$m.ko"
  MAGIC="$(od -An -tx1 -N4 "$KO" 2>/dev/null | tr -d ' \n')"
  EM="$(od -An -tx1 -j18 -N2 "$KO" 2>/dev/null | tr -d ' \n')"
  { [ "$MAGIC" = "7f454c46" ] && [ "$EM" = "b700" ]; } || abort "! $m.ko is not an AArch64 ELF"
done
ui_print "- driver payload OK (cfg80211 + mac80211 + wlan)"

set_perm_recursive "$MODPATH/modules" 0 0 0755 0644
for s in post-fs-data.sh service.sh common.sh action.sh uninstall.sh; do
  [ -f "$MODPATH/$s" ] && set_perm "$MODPATH/$s" 0 0 0755
done

# Install the Stryker malachite fork app (it carries its own capture/injection tools).
if [ -f "$MODPATH/stryker.apk" ]; then
  if pm path android >/dev/null 2>&1; then
    ui_print "- Installing the Stryker malachite app"
    if pm install -r -g "$MODPATH/stryker.apk" >/dev/null 2>&1; then
      ui_print "  app installed / updated"
    else
      ui_print "  app install deferred to first boot"
    fi
  else
    ui_print "- No framework now; the app installs on first boot"
  fi
fi

ui_print " "
ui_print "- REBOOT. Keep Wi-Fi ON and SELinux enforcing."
ui_print "- Tap Action to launch Stryker; use wlan0 for monitor."
ui_print "- Driver result: su -c 'cat /data/adb/malachite_wifi_monitor/swap.log'"
ui_print "- Remove the old separate driver/backend modules if present."
ui_print " "
