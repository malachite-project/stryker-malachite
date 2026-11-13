# Stryker malachite — internal-adapter monitor mode + packet injection

Native **monitor mode** and **packet injection** on the **internal** Wi‑Fi of the
Xiaomi *malachite* (MediaTek **MT6878 / CONNAC2 "gen4m"**) — no external USB
adapter. This repository is the complete package:

| Path | What it is |
|------|------------|
| `app/` | The Stryker fork (Android app) with the malachite monitor commands wired to the native **nl80211 (`iw`)** path. |
| `kernel-patches/` | Source changes to the MediaTek **gen4m** Wi‑Fi driver that unlock monitor RX (radiotap) and raw 802.11 injection. |
| `ksu-module/` | A KernelSU/Magisk module that loads the patched driver early at boot and installs + launches the app. |

## How it works

1. The **driver** (`kernel-patches/`) enables the sniffer/radiotap RX path and a
   management‑frame TX path in the gen4m driver.
2. The **module** (`ksu-module/`) loads the built driver modules
   (`cfg80211` + `mac80211` + `wlan_drv_gen4m_6878`) at `post-fs-data`, *before*
   the Wi‑Fi HAL starts, so `wlan0` comes up with monitor support on the normal
   boot path. It falls back to the stock driver automatically if ours fails to
   load, so Wi‑Fi is never left broken.
3. The **app** drives `wlan0` into monitor with `iw` (type switch + `HT20`
   channel; it never turns Wi‑Fi off, because on gen4m that removes `wlan0`), and
   uses its own tooling for capture and injection.

Build the driver from `kernel-patches/` (see `kernel-patches/BUILD.md`), drop the
resulting `.ko` into `ksu-module/modules/`, add the built app APK, and zip the
module. `.ko`/`.apk` are not tracked here — attach them to a Release.

## Credits

Built on the open‑source **Stryker** app by zalexdev and the **aircrack‑ng**
suite. See `THIRD-PARTY-NOTICES.md` and `LICENSE` for upstream licensing.

## Legal

For **authorized security testing on your own devices and networks only.**
