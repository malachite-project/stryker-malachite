package com.zalexdev.stryker.utils;

import android.content.Context;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.custom.UsbDev;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class MonitorManager {
    private static final Object RADIO_TRANSITION_LOCK = new Object();
    public Context context;
    public Core core;
    public Logger logger;

    private String nexutilPath() {
        return "$(if [ -x /system/bin/nexutil ]; then echo /system/bin/nexutil; "
                + "else echo /data/data/com.zalexdev.stryker.devicefork/files/nexutil_device; fi)";
    }

    // MT6878/gen4m (malachite): native nl80211 monitor via iw. No nexutil, no svc wifi disable.
    public UsbDev internalUsbDev = new UsbDev("wlan0", "wlan0", "ip link set wlan0 down; iw dev wlan0 set type monitor; ip link set wlan0 up; iw dev wlan0 set channel $ch HT20", "ip link set wlan0 down; iw dev wlan0 set type managed; ip link set wlan0 up");
    public UsbDev internalUsbSamsung = new UsbDev("swlan0","swlan0","ip link set $ifc down; echo '4' > /sys/module/wlan/parameters/con_mode; ip link set $ifc up","ip link set $ifc down; echo '0' > /sys/module/wlan/parameters/con_mode");

    public MonitorManager(Core core) {
        this.core = core;
        this.context = core.context;
        this.logger = core.logger;
        getDevices();
    }


    public static final String IW_PAIRS =
            "iw dev 2>/dev/null | awk '$1==\"Interface\"{n=$2} $1==\"type\"{if(n!=\"\"){print n\",\"$2; n=\"\"}}'";

    public ArrayList<String[]> listInterfaces(){
        ArrayList<String[]> out = new ArrayList<>();
        for (String t : core.customChrootCommand(IW_PAIRS, true)){
            if (t == null) continue;
            String line = t.trim();
            int c = line.indexOf(',');
            if (c <= 0 || c == line.length() - 1) continue;
            out.add(new String[]{line.substring(0, c).trim(), line.substring(c + 1).trim()});
        }
        return out;
    }

    public static boolean isInternalRadio(String ifc){
        return ifc != null && ifc.matches("(wlan0|swlan0)(mon)?");
    }

    private String baseOf(String ifc){
        return (ifc != null && ifc.endsWith("mon")) ? ifc.substring(0, ifc.length() - 3) : ifc;
    }

    // 1 is Ethernet/managed, 803 is radiotap. Anything else is unknown.
    // An unreadable or absent interface must never be treated as a successful stop.
    private int internalInterfaceType() {
        // gen4m may not report ARPHRD 803 in /sys even while in monitor, so trust the
        // authoritative iw view first (confirmed to report "monitor" on malachite);
        // fall back to /sys ARPHRD. 803 => monitor, 1 => managed/other, -1 => absent.
        for (String line : core.customCommand("iw dev wlan0 info 2>/dev/null | awk '$1==\"type\"{print $2}'", true)) {
            String t = line.trim();
            if ("monitor".equals(t)) return 803;
            if (t.length() > 0) return 1;
        }
        for (String line : core.customCommand("cat /sys/class/net/wlan0/type 2>/dev/null", true)) {
            if ("1".equals(line.trim())) return 1;
            if ("803".equals(line.trim())) return 803;
        }
        return -1;
    }

    private String monitorInterfaceFor(String ifc){
        if (ifc == null || ifc.isEmpty()) return null;
        String base = baseOf(ifc);
        if ("wlan0".equals(ifc) && !core.isRootless()) {
            return internalInterfaceType() == 803 ? ifc : null;
        }
        for (String[] p : listInterfaces()){
            if (!p[1].contains("monitor")) continue;
            if (p[0].equals(ifc) || p[0].equals(base) || p[0].equals(base + "mon")) return p[0];
        }
        return null;
    }

    private boolean forceManaged(String ifc){
        core.customChrootCommand("ip link set " + ifc + " down 2>/dev/null; "
                + "iw dev " + ifc + " set type managed 2>/dev/null; "
                + "ip link set " + ifc + " up 2>/dev/null");
        return monitorInterfaceFor(ifc) == null;
    }

    public boolean disableMonitorMode(String interfaceName){
        synchronized (RADIO_TRANSITION_LOCK) {
            return disableMonitorModeLocked(interfaceName);
        }
    }

    private boolean disableMonitorModeLocked(String interfaceName){
        if (core.isRootless()) {
            return disableMonitorModeRootless(interfaceName);
        }
        logger.writeLine("Disabling monitor mode on interface: " + interfaceName,1);
        String mon = monitorInterfaceFor(interfaceName);
        if ("wlan0".equals(interfaceName)) {
            core.customCommand("ip link set wlan0 down; iw dev wlan0 set type managed; ip link set wlan0 up", true);
        } else {
            core.customChrootCommand(getDisableCommand(mon != null ? mon : interfaceName));
        }
        String left = monitorInterfaceFor(interfaceName);
        if ("wlan0".equals(interfaceName) && internalInterfaceType() != 1) {
            logger.writeLine("Cannot confirm wlan0 returned to managed mode; recovery remains pending", 3);
            return false;
        }
        boolean stillMon = left != null && ("wlan0".equals(interfaceName) || !forceManaged(left));
        if (isInternalRadio(interfaceName)) {
            android.content.SharedPreferences recovery = context.getSharedPreferences("monitor_recovery", Context.MODE_PRIVATE);
            if (!stillMon && recovery.getBoolean("restore_pending", false)) {
                if (recovery.getBoolean("wifi_was_on", false)) {
                    ArrayList<String> result = core.customCommand("svc wifi enable && echo STRYKER_WIFI_RESTORE_OK", true);
                    if (!result.contains("STRYKER_WIFI_RESTORE_OK")) {
                        logger.writeLine("Wi-Fi restoration failed; recovery remains pending", 3);
                        return false;
                    }
                }
                recovery.edit().remove("restore_pending").apply();
            }
        }
        if (stillMon) {
            logger.writeLine("Interface " + interfaceName + " is still in monitor mode", 3);
        }
        return !stillMon;
    }
    public boolean enableMonitorMode(String interfaceName){
        logger.writeLine("Enabling monitor mode on interface: " + interfaceName,1);
        synchronized (RADIO_TRANSITION_LOCK) {
            if (core.isRootless()) return enableMonitorModeRootless(interfaceName, null);
            return enableMonitorModeRoot(interfaceName, null);
        }
    }

    private boolean enableMonitorModeRoot(String ifc, String channel){
        if ("wlan0".equals(ifc) && internalInterfaceType() == -1) {
            logger.writeLine("Cannot read wlan0 state; refusing to change Wi-Fi", 3);
            return false;
        }
        if (channel != null && !channel.isEmpty() && !"0".equals(channel)) {
            if (!channel.matches("[0-9]{1,3}")) return false;
            int requested = Integer.parseInt(channel);
            if (!((requested >= 1 && requested <= 14) || (requested >= 32 && requested <= 196))) return false;
        }
        if (isMonitorModeEnabled(ifc)){
            logger.writeLine("Monitor mode is already enabled on interface: " + ifc,1);
            return lockChannel(ifc, channel);
        }
        if (isInternalRadio(ifc)) {
            ArrayList<String> wifiState = core.customCommand("settings get global wifi_on", true);
            if (!wifiState.contains("1") && !wifiState.contains("0")) {
                logger.writeLine("Cannot determine Wi-Fi state; monitor mode was not started", 3);
                return false;
            }
            boolean wifiWasOn = wifiState.contains("1");
            android.content.SharedPreferences recovery = context.getSharedPreferences("monitor_recovery", Context.MODE_PRIVATE);
            if (!recovery.getBoolean("restore_pending", false)) {
                recovery.edit().putBoolean("wifi_was_on", wifiWasOn)
                        .putBoolean("restore_pending", true).commit();
            }
            // MT6878/gen4m: do NOT disable Wi-Fi here - turning Wi-Fi off removes the
            // wlan0 netdev on this driver. Keep it on; we only flip the interface type.
        }
        String startCommand = monitorCommandFor(ifc, channel);
        if ("wlan0".equals(ifc)) {
            // Host root: iw/ip must act on the real wlan0, not inside the chroot.
            core.customCommand(startCommand, true);
        } else {
            core.customChrootCommand(startCommand);
        }
        if ("wlan0".equals(ifc) && isMonitorModeEnabled(ifc)) {
            String selected = (channel == null || channel.isEmpty() || "0".equals(channel)) ? "6" : channel;
            if (selected.matches("[0-9]{1,3}")) {
                context.getSharedPreferences("monitor_recovery", Context.MODE_PRIVATE).edit()
                        .putInt("monitor_channel", Integer.parseInt(selected)).apply();
            }
        }
        boolean enabled = lockChannel(ifc, channel);
        if (!enabled && isInternalRadio(ifc)) disableMonitorMode(ifc);
        return enabled;
    }

    private String monitorCommandFor(String ifc, String channel){
        if ("wlan0".equals(ifc)) {
            String selected = (channel == null || channel.isEmpty() || "0".equals(channel)) ? "6" : channel;
            if (!selected.matches("[0-9]{1,3}")) return "false";
            // gen4m: flip the live wlan0 to monitor via iw (NO svc wifi disable - that
            // deletes wlan0 on this driver). One warm-up type cycle settles the
            // firmware (first arm often yields 0 frames), then arm + set channel.
            String mon = "ip link set wlan0 down; iw dev wlan0 set type monitor; ip link set wlan0 up; iw dev wlan0 set channel " + selected + " HT20";
            String mgd = "ip link set wlan0 down; iw dev wlan0 set type managed; ip link set wlan0 up";
            return mon + "; sleep 1; " + mgd + "; sleep 1; " + mon;
        }
        return (channel == null || channel.isEmpty() || "0".equals(channel))
                ? getMonitorCommand(ifc) : getMonitorCommand(ifc, channel);
    }

    private boolean enableMonitorModeRootless(String ifc, String channel){
        com.zalexdev.stryker.engine.GuestEngine guest = core.guest();
        if (!guest.ensureUsbWifiAttached()) {
            logger.writeLine("No USB Wi-Fi adapter in " + guest.displayName()
                    + " — cannot enable monitor mode", 3);
            return false;
        }
        if (!isMonitorModeEnabled(ifc)) {
            StringBuilder cmd = new StringBuilder();
            cmd.append("rfkill unblock all 2>/dev/null || for f in /sys/class/rfkill/*/state; do echo 1 > \"$f\" 2>/dev/null; done; ");
            cmd.append("airmon-ng check kill >/dev/null 2>&1; ");
            cmd.append("ip link set ").append(ifc).append(" down 2>/dev/null; ");
            cmd.append("iw dev ").append(ifc).append(" set type monitor 2>/dev/null || airmon-ng start ").append(ifc).append("; ");
            cmd.append("ip link set ").append(ifc).append(" up 2>/dev/null");
            core.customChrootCommand(cmd.toString());
        }
        return lockChannel(ifc, channel);
    }

    private boolean lockChannel(String ifc, String channel){
        String monIfc = null;
        if (isMonitorModeEnabled(ifc)) monIfc = ifc;
        else if (isMonitorModeEnabled(ifc + "mon")) monIfc = ifc + "mon";
        if (monIfc == null) return false;
        if (channel != null && !channel.isEmpty() && !"0".equals(channel)) {
            if (!channel.matches("[0-9]{1,3}")) return false;
            if ("wlan0".equals(monIfc)) {
                int requested = Integer.parseInt(channel);
                if (!((requested >= 1 && requested <= 14) || (requested >= 32 && requested <= 196))) return false;
                // gen4m: set the monitor channel with iw on the host. Explicit HT20 -
                // a bare 'set channel' defaults to NOHT, which the driver rejects.
                ArrayList<String> result = core.customCommand("iw dev wlan0 set channel " + channel
                        + " HT20 && echo STRYKER_CHANNEL_OK", true);
                if (!result.contains("STRYKER_CHANNEL_OK")) {
                    logger.writeLine("Could not set channel " + channel + " on wlan0", 3);
                    return false;
                }
                context.getSharedPreferences("monitor_recovery", Context.MODE_PRIVATE)
                        .edit().putInt("monitor_channel", requested).apply();
            } else {
                ArrayList<String> result = core.customChrootCommand("iw dev " + monIfc + " set channel " + channel
                        + " && echo STRYKER_CHANNEL_OK", true);
                if (!result.contains("STRYKER_CHANNEL_OK")) {
                    logger.writeLine("Could not set channel " + channel + "; band changes may require re-arming", 3);
                    return false;
                }
            }
        }
        return true;
    }

    private boolean disableMonitorModeRootless(String ifc){
        logger.writeLine("Disabling monitor mode (rootless) on interface: " + ifc, 1);
        String base = baseOf(ifc);
        String mon = monitorInterfaceFor(ifc);
        String target = mon != null ? mon : ifc;
        core.customChrootCommand("airmon-ng stop " + target + " 2>/dev/null; "
                + "ip link set " + target + " down 2>/dev/null; "
                + "iw dev " + target + " set type managed 2>/dev/null; "
                + "ip link set " + target + " up 2>/dev/null; "
                + "ip link set " + base + " up 2>/dev/null");
        String left = monitorInterfaceFor(ifc);
        boolean stillMon = left != null && !forceManaged(left);
        if (stillMon) {
            logger.writeLine("Interface " + ifc + " is still in monitor mode", 3);
        }
        return !stillMon;
    }

    public boolean isMonitorModeEnabled(String interfaceName){
        if (interfaceName == null || interfaceName.isEmpty()) return false;
        if ("wlan0".equals(interfaceName) && !core.isRootless()) return internalInterfaceType() == 803;
        for (String[] p : listInterfaces()){
            if (p[0].equals(interfaceName)) return p[1].contains("monitor");
        }
        return false;
    }
    public boolean enableMonitorMode(String interfaceName,String channel){
        logger.writeLine("Enabling monitor mode on interface: " + interfaceName+ " on channel "+channel,1);
        synchronized (RADIO_TRANSITION_LOCK) {
            if (core.isRootless()) return enableMonitorModeRootless(interfaceName, channel);
            return enableMonitorModeRoot(interfaceName, channel);
        }
    }

    public String getHSInterface(){
        String scanInterface = core.getString("wlan_scan");
        if (scanInterface == null || scanInterface.isEmpty()) scanInterface = "wlan0";
        if (core.getInterfacesList().contains(scanInterface+"mon")){
            logger.writeLine("Scan interface is "+scanInterface+"mon",2);
            return scanInterface+"mon";
        }else{
            logger.writeLine("Scan interface is "+scanInterface,2);
            return scanInterface;
        }
    }
    public String getDeauthInterface(){
        String wlanDeauth = core.getString("wlan_deauth");
        if (wlanDeauth == null || wlanDeauth.isEmpty()) wlanDeauth = "wlan0";
        if (core.getInterfacesList().contains(wlanDeauth+"mon")){
            logger.writeLine("Deauth interface is "+wlanDeauth+"mon",2);
            return wlanDeauth+"mon";
        }else{
            logger.writeLine("Deauth interface is "+wlanDeauth,2);
            return wlanDeauth;
        }
    }
    public String getPid(){
        String deviceId = null;
        try {

            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> devices = manager.getDeviceList();
            for (String deviceName : devices.keySet()) {
                UsbDevice device = devices.get(deviceName);
                assert device != null;
                StringBuilder string2 = new StringBuilder(Integer.toHexString(device.getVendorId()));
                while (string2.length() < 4) {
                    string2.insert(0, "0");
                }
                StringBuilder string3 = new StringBuilder(Integer.toHexString(device.getProductId()));
                while (string3.length() < 4) {
                    string3.insert(0, "0");
                }
                deviceId = string2 + ":" + string3;
            }
            return deviceId;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public ArrayList<String> getPids(){
        ArrayList<String> out = new ArrayList<>();
        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (device == null) continue;
                out.add(String.format(Locale.ROOT, "%04x:%04x",
                        device.getVendorId(), device.getProductId()));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public void addDevice(UsbDev usbDev){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (!devices.contains(deviceToString)){
            devices.add(deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void addDevice(UsbDev usbDev, int index){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (!devices.contains(deviceToString)){
            if (index < 0 || index >= devices.size()) {
                logger.writeLine("Cannot update an adapter that no longer exists", 3);
                return;
            }
            devices.set(index,deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void removeDevice(UsbDev usbDev){
        String deviceToString = usbDev.getIfc()+"---"+usbDev.getPid()+"---"+usbDev.getCommandMon()+"---"+usbDev.getCommandDis();
        ArrayList<String> devices = core.getListString("devices");
        if (devices.contains(deviceToString)){
            devices.remove(deviceToString);
            core.putListString("devices",devices);
        }
    }
    public void removeDevice(int id){
        ArrayList<String> devices = core.getListString("devices");
        devices.remove(id);
        core.putListString("devices",devices);
    }
    public void changeDevice(UsbDev usbDev, int id){
        addDevice(usbDev,id);
    }
    public ArrayList<UsbDev> getDevices(){
        ArrayList<UsbDev> result = new ArrayList<>();
        ArrayList<String> devices = core.getListString("devices");
        if (devices.isEmpty()){
            addDevice(internalUsbDev);
            addDevice(internalUsbSamsung);
            devices = core.getListString("devices");
        }
        for (String s : devices){
            String[] l = s.split("---");
            result.add(new UsbDev(l[0],l[1],l[2],l[3]));
        }

        return result;
    }
    public boolean isDeviceAdded(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<4){
        for (UsbDev d : devices){
            if (d.getIfc().equals(ifc)){
                return true;
            }
        }}else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    return true;
                }
            }
        }
        return false;
    }
    public String getMonitorCommand(String ifc){
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    core.logger.writeLine("Found device: "+d.getIfc()+" with command: "+d.getCommandMon(),2);
                    return d.getCommandMon().replace("$ch","");
                }
            }}
        else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    core.logger.writeLine("Found device: "+d.getIfc()+" with command: "+d.getCommandMon(),2);
                    return d.getCommandMon().replace("$ch","");
                }
            }
        }
        if(ifc.equals("wlan0")){
            core.logger.writeLine("Found device: "+internalUsbDev.getIfc()+" with command: "+internalUsbDev.getCommandMon(),2);
            return internalUsbDev.getCommandMon().replace("$ch","");
        }
        if(ifc.equals("swlan0")){
            core.logger.writeLine("Found device: "+internalUsbSamsung.getIfc()+" with command: "+internalUsbSamsung.getCommandMon(),2);
            return internalUsbSamsung.getCommandMon().replace("$ch","");
        }
        return "airmon-ng start "+ifc;
    }

    public String getMonitorCommand(String ifc, String channel){
        String ch = channel == null ? "" : channel;
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    return d.getCommandMon().replace("$ch",ch);
                }
            }
        }else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    return d.getCommandMon().replace("$ch",ch);
                }
            }
        }
        if(ifc.equals("wlan0")){
            return internalUsbDev.getCommandMon().replace("$ch",ch);
        }
        if(ifc.equals("swlan0")){
            return internalUsbSamsung.getCommandMon().replace("$ch",ch);
        }
        return "airmon-ng start "+ifc + " " + ch;
    }


    public String getDisableCommand(String ifc){
        if (ifc != null && ifc.endsWith("mon") && isInternalRadio(ifc)) {
            ifc = ifc.substring(0, ifc.length() - 3);
        }
        ArrayList<UsbDev> devices = getDevices();
        String pid = getPid();
        ArrayList<String> pids = getPids();
        if (pid==null||pid.equals("Unknown")||pid.length()<6){
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)){
                    return d.getCommandDis();
                }
            }}else{
            for (UsbDev d : devices){
                if (d.getIfc().equals(ifc)&&pids.contains(d.getPid())){
                    return d.getCommandDis();
                }
            }
        }
        if(ifc.equals("wlan0")){
            return internalUsbDev.getCommandDis();
        }
        if(ifc.equals("swlan0")){
            return internalUsbSamsung.getCommandDis();
        }
        return "airmon-ng stop "+ifc;
    }



}
