#!/system/bin/sh
# malachite Wi-Fi monitor+injection v1.3 - EARLY driver load (post-fs-data).
#
# Why here and not service.sh: post-fs-data runs before the Wi-Fi HAL and the
# framework start. If our gen4m stack is the loaded one by then, the HAL binds
# to OURS and brings wlan0 up through the normal boot path - no `svc`, no hot
# swap under a live HAL (both of which broke v1.2 on-device).
#
# Strategy:
#   1) bind-mount our 3 .ko over the stock files so init's modprobe loads OURS;
#   2) if the stock stack is already resident this early, rmmod + insmod ours
#      now (nothing holds them before the HAL);
#   3) never call svc - let normal boot create wlan0 on our driver;
#   4) fail-safe: if ours won't insmod, restore stock so boot keeps working.

MODDIR=${0%/*}
KO="$MODDIR/modules"
SD=/data/adb/malachite_wifi_monitor
mkdir -p "$SD"; chmod 700 "$SD"
LOG="$SD/swap.log"
[ -f "$LOG" ] && mv -f "$LOG" "$SD/swap.log.1" 2>/dev/null
: > "$LOG"; exec >>"$LOG" 2>&1
log(){ echo "[$(date +%H:%M:%S)] $*"; }
loaded(){ grep -q "^$1 " /proc/modules 2>/dev/null; }
mods(){ grep -E '^(wlan_drv_gen4m_6878|mac80211|cfg80211) ' /proc/modules 2>/dev/null | awk '{print $1"("$3")"}' | tr '\n' ' '; }
avc(){ D=$(dmesg 2>/dev/null | grep -iE 'avc: .*denied' | grep -iE 'module_load|sys_module|\.ko|cfg80211|mac80211|wlan_drv' | tail -8); [ -n "$D" ] && { log "---- AVC (module) ----"; echo "$D"; log "----------------------"; }; }

log "==== malachite early driver load (v1.3, post-fs-data) ===="
log "entry state: [$(mods)]"

# Need a complete restorable stock set AND a complete payload, or we touch nothing.
STOCKDIR=""
for d in /vendor_dlkm/lib/modules /vendor/lib/modules /system/vendor_dlkm/lib/modules /odm/lib/modules; do
  if [ -f "$d/wlan_drv_gen4m_6878.ko" ] && [ -f "$d/cfg80211.ko" ] && [ -f "$d/mac80211.ko" ]; then
    STOCKDIR="$d"; break
  fi
done
if [ -z "$STOCKDIR" ]; then
  log "ABORT (safe): no restorable stock dir -> leaving stock untouched."; exit 0
fi
for m in cfg80211 mac80211 wlan_drv_gen4m_6878; do
  [ -f "$KO/$m.ko" ] || { log "ABORT (safe): payload $m.ko missing -> leaving stock untouched."; exit 0; }
done
log "stock dir: $STOCKDIR"

# Match our .ko SELinux context to the stock files (so the loader accepts them,
# and so the bind-mount presents the expected context to init's modprobe).
CTX=$(ls -Zd "$STOCKDIR/wlan_drv_gen4m_6878.ko" 2>/dev/null | awk '{print $1}')
case "$CTX" in u:object_r:*) chcon "$CTX" "$KO"/*.ko 2>/dev/null && log "ctx set to $CTX";; *) log "ctx: unreadable (continuing)";; esac

# (1) Bind-mount ours over the stock files. If init has not loaded wifi yet, it
#     will now load OURS; if it already did, this makes the swap stick for the
#     rest of this boot's file reads and for next boot.
BOUND=0
for m in cfg80211 mac80211 wlan_drv_gen4m_6878; do
  if mount -o bind "$KO/$m.ko" "$STOCKDIR/$m.ko" 2>/dev/null; then BOUND=$((BOUND+1)); else log "bind $m FAILED"; fi
done
log "bind-mounted $BOUND/3 over $STOCKDIR"

restore_stock(){
  log "!! restoring stock"
  for m in wlan_drv_gen4m_6878 mac80211 cfg80211; do umount "$STOCKDIR/$m.ko" 2>/dev/null; done
  rmmod wlan_drv_gen4m_6878 2>/dev/null; rmmod mac80211 2>/dev/null; rmmod cfg80211 2>/dev/null
  insmod "$STOCKDIR/cfg80211.ko" 2>/dev/null
  insmod "$STOCKDIR/mac80211.ko" 2>/dev/null
  insmod "$STOCKDIR/wlan_drv_gen4m_6878.ko" 2>/dev/null
  log "restore state: [$(mods)]"
}

# (2) If the stock stack is already resident this early, reload ours now. This
#     is safe pre-HAL (nothing holds the modules yet). If it won't unload, the
#     bind-mount from (1) still makes next boot load ours; leave this boot alone.
if loaded wlan_drv_gen4m_6878 || loaded cfg80211; then
  log "stock already resident at post-fs-data -> active reload before HAL"
  n=0; until ! loaded wlan_drv_gen4m_6878 || [ $n -ge 5 ]; do rmmod wlan_drv_gen4m_6878 2>/dev/null; n=$((n+1)); sleep 1; done
  rmmod mac80211 2>/dev/null
  rmmod cfg80211 2>/dev/null
  if loaded wlan_drv_gen4m_6878 || loaded cfg80211; then
    log "could not fully unload (held) -> keeping stock this boot; bind-mount loads ours next boot. [$(mods)]"
    exit 0
  fi
  insmod "$KO/cfg80211.ko"; a=$?; log "insmod cfg80211 rc=$a"
  insmod "$KO/mac80211.ko"; b=$?; log "insmod mac80211 rc=$b"
  insmod "$KO/wlan_drv_gen4m_6878.ko"; c=$?; log "insmod wlan rc=$c"
  if [ "$a" -ne 0 ] || [ "$c" -ne 0 ]; then
    log "ours failed to load"; avc; restore_stock; exit 0
  fi
  avc
  log "OURS loaded early: [$(mods)]. HAL will bind to ours; wlan0 comes up on normal boot. No svc called."
else
  log "stock not yet resident -> init will load OURS from the bind-mount. No svc called."
fi
log "==== early load done ===="
