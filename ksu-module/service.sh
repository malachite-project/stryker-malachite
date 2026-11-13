#!/system/bin/sh
# Combined module: (A) verify the early-loaded driver, (B) first-boot app install.
# The driver swap itself happens in post-fs-data.sh; we never swap or svc here.
MODDIR=${0%/*}
SD=/data/adb/malachite_wifi_monitor; mkdir -p "$SD"; chmod 700 "$SD"
LOG="$SD/swap.log"
log(){ echo "[$(date +%H:%M:%S)] $*" >>"$LOG"; }
mods(){ grep -E '^(wlan_drv_gen4m_6878|mac80211|cfg80211) ' /proc/modules 2>/dev/null | awk '{print $1"("$3")"}' | tr '\n' ' '; }

# (A) verify driver after boot
(
  i=0; while [ "$(getprop sys.boot_completed)" != 1 ] && [ $i -lt 120 ]; do sleep 2; i=$((i+2)); done
  j=0; while ! ip link show wlan0 >/dev/null 2>&1 && [ $j -lt 30 ]; do sleep 2; j=$((j+2)); done
  log "---- verify (+$((i+j))s after boot) ----"
  log "modules: [$(mods)]"
  if ip link show wlan0 >/dev/null 2>&1; then log "wlan0: UP/present"; else log "wlan0: NOT present - disable module + reboot for stock Wi-Fi"; fi
  log "monitor-capable=$(iw phy 2>/dev/null | grep -ci '\* monitor') (>=1 => OURS)"
  log "==== verify done ===="
) &

# (B) first-boot install of the Stryker fork app if not present
if [ -f "$MODDIR/stryker.apk" ]; then
  (
    i=0; while [ $i -lt 30 ]; do pm path android >/dev/null 2>&1 && break; sleep 2; i=$((i+1)); done
    pm path com.zalexdev.stryker.devicefork >/dev/null 2>&1 || pm install -r -g "$MODDIR/stryker.apk" >/dev/null 2>&1
  ) &
fi
