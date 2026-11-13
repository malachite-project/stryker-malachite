# Wi-Fi monitor mode + injection — build & integration

Goal: native monitor mode (radiotap RX) **and** packet injection (raw 802.11 TX)
on malachite's **internal** MT6878 / CONNAC2 "gen4m" Wi-Fi — the capability the
Galaxy S21 (o1s) got from Nexmon, done here without an external adapter.

Background and the full technical rationale:
`../notes/2026-11-10-wifi-injection-handoff.md` and `../notes/2026-11-10-wifi-monitor-mode.md`.

## Patches (split so the first test build is passive-only)

| File | What it does |
| --- | --- |
| `0001-gen4m-passive-monitor.patch` | `Kbuild.6878`: `CFG_SUPPORT_SNIFFER_RADIOTAP` + `CFG_SUPPORT_PDMA_SCATTER`. **Monitor RX only — no TX behaviour changes.** This is the first-test (passive) build. |
| `patches/blocked/0002-gen4m-packet-injection.patch` (BLOCKED — see FINDINGS-2026-11-12.md) | `Kbuild.6878`: adds `CFG_SUPPORT_TX_MGMT_USE_DATAQ=1`; and `os/linux/gl_init.c` `wlanHardStartXmit`: strips radiotap and submits the raw 802.11 frame via the firmware's 802.11 data-path TX. Apply **on top of 0001** only for the injection build. |
| `sniffer.fragment.config` | Kernel config fragment (`CONFIG_SNIFFER_RADIOTAP=y`). |

The KSU test module that delivers the built driver + native tools:
`../ksu-module/malachite-wifi-monitor/`.

## How the two halves work

- **Monitor RX (capture):** MediaTek's own driver already implements it behind
  `CFG_SUPPORT_SNIFFER_RADIOTAP` (interface type monitor, `set_monitor_channel`
  → `CMD_ID_SET_MONITOR` to firmware, radiotap RX). Xiaomi shipped it off.
  Patch 0001 turns it on.
- **Injection (TX):** CONNAC2's TX descriptor supports `HEADER_FORMAT_802_11_*`,
  i.e. the chip can transmit an already-formed 802.11 frame rather than doing
  802.3→802.11 conversion. The driver uses this for management frames: an skb
  flagged `ENUM_PKT_802_11_MGMT` becomes an `MSDU_INFO` with `fgIs802_11=TRUE`
  (`nic_tx.c:3459`, gated by `CFG_SUPPORT_TX_MGMT_USE_DATAQ`). Patch 0002 routes
  radiotap-injected frames down that same path. No firmware patch is required for
  the mechanism. **Status: unbuilt and unverified** — the monitor-mode BSS
  binding and the firmware's willingness to emit a spoofed source address are
  on-device unknowns (see Verify, below).

## Apply the patches

Patches are made against the gen4m source
(`github.com/mt6878-devs/android_vendor_mediatek_kernel_modules`, tree matching
local `src/ref_mt6878devs_kernel_modules`, branch `lineage-23.2`). Apply inside
whatever `kernel_modules` checkout the kernel build consumes, from its repo root:

```sh
# Passive monitor RX build (the only one to build now):
git apply --ignore-whitespace 0001-gen4m-passive-monitor.patch
```

Do **not** build `patches/blocked/0002-gen4m-packet-injection.patch` — it does not
compile at this commit and cannot transmit in monitor mode (see
`patches/blocked/README.md` and `../FINDINGS-2026-11-12.md`).

`--ignore-whitespace` matters: the source uses CRLF and the patch is
normalised to ignore CR-at-EOL. It was `git apply --check`-verified in
sequence against the pristine tree.

## Build the module (no local pipeline — this is the gating step)

`device/xiaomi/malachite-kernel` is a **prebuilt** repo; the kernel + modules are
built from `kernel_manifest-6.1` in CI, not locally. To produce a
`wlan_drv_gen4m_6878.ko`:

1. Check out the `kernel_manifest-6.1` sources used for malachite (the manifest
   referenced in `device/xiaomi/malachite-kernel/SOURCE_MIGRATION.md`).
2. Apply 0001 (and 0002 for the injection build) to its
   `vendor/mediatek/kernel_modules` gen4m tree. Add `sniffer.fragment.config` to
   the kernel config if the build selects module flags via Kconfig (0001 also
   forces it in Kbuild.6878).
3. Build (Kleaf/Bazel, as the CI workflow does) against the **ACK 6.1.166** KMI
   that our `Image.lz4` exposes.
4. **ABI/CRC gate — validate against the ACTUAL module set, not just four names.**
   `modinfo` the prebuilt `wlan_drv_gen4m_6878.ko` for its full `depends:` list —
   it includes `wmt_chrdev_wifi_connac2, connadp, cfg80211, mtk_disp_notify,
   pmic_lbat_service, conninfra, aee_aed, ccci_md_all, mddp, connfem`. The rebuilt
   module's modversions CRC for **every** imported symbol must match the exact
   kernel + module set in the test image, or it will not load. Keep the first
   build to the **single** WLAN replacement; only if CRCs force it, rebuild the
   dependency modules from the same source (that greatly expands the compatibility
   work) and re-check with
   `device/xiaomi/malachite-kernel/tools/compare_module_coverage.py`. Note the
   project's known coverage GAP for from-source kernels. (Android KMI-at-runtime:
   https://source.android.com/docs/core/architecture/kernel/abi-monitor#enforce-the-kmi-at-runtime)
5. **First test = vendor_dlkm swap, not the KSU bind mount.** The ROM's module
   loader (`init.insmod.rc`) loads the WLAN driver early, so a KSU post-fs-data
   bind mount is not guaranteed to win the race and can give a false pass. For the
   first image, put the built (passive) `.ko` in the test ROM's `vendor_dlkm` so
   the ROM's own loader uses it, and **verify the loaded driver's identity**
   on-device: `modinfo wlan_drv_gen4m_6878 | grep srcversion` must match the build.
   Use the KSU module (step 6) only once the overlay is proven to win the race.
6. Once proven: drop the built `wlan_drv_gen4m_6878.ko` into
   `../ksu-module/malachite-wifi-monitor/modules/` (installer requires an AArch64
   ELF), add native aircrack-ng tools under `tools/` (reuse WSL
   `~/native-wireless/out/assets/`), and package with `tools/make_ksu_module.py`.

## Verify (tester device — we have no phone)

Go/no-go order, each needs a tester; capture is NEVER proof of injection:

1. **Boots + Wi-Fi still works** with the replacement driver (ABI OK, loaded
   driver identity confirmed).
2. **Monitor RX (0001 build):** `iw dev wlan0 info` shows type monitor;
   `airodump-ng wlan0` logs frames on ch 6 and ch 36. The driver discards
   `kalIoctl()`'s result and returns success, so interface type alone does NOT
   prove the channel took — confirm real frame reception. Use explicit `HT20`
   (bare `iw set channel` defaults to `NOHT`, which the driver's width switch
   rejects). If 0 frames, the stock firmware may not honour `CMD_ID_SET_MONITOR`
   — that reopens a firmware angle.
3. **Injection (0002 build):** `aireplay-ng -9 wlan0` → "Injection is working!";
   then a targeted deauth → WPA2 handshake capture against an **authorized** test
   AP. Watch for the firmware stamping its own source address (would break spoofed
   deauth) and for the monitor-mode BSS-binding fallback in patch 0002 (the one
   piece flagged for on-device bring-up).
4. **SELinux enforcing:** capture real AVCs (`dmesg | grep avc`) and fill
   `../ksu-module/malachite-wifi-monitor/sepolicy.rule`; do not blanket-permissive.

## If injection via the data path is refused by firmware

If route-A injection (patch 0002) is rejected on-device (firmware won't emit a
spoofed/arbitrary frame), the remaining route is firmware reverse-engineering of
`soc7_0_ram_mcu_*.bin` — see §5 "Route B" of the handoff, including the
firmware-signing hard-stop to check first.
