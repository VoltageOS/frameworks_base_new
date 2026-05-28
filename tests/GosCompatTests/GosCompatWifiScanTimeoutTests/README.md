# GosCompatWifiScanTimeoutTests

This module is a targeted regression test for a Pixel `bcmdhd` scan timeout kernel KASAN MTE panic 
in the `wl_escan_handler` path.

Requirements:

- The test uses the `gos-dhdutil` vendor debug helper to arm the driver timeout hook before the
scan request via the Broadcom DHD `induce_error` iovar. This helper is only installed on 
userdebug/eng builds (or you can revert the adevtool commit that restricts it to userdebug/eng),
so this test will only work on builds with `gos-dhdutil`
- Enable Wi-Fi and Location
- Keep screen on since test uses a foreground Activity to do Wi-Fi scans
- The KASAN panic only happens on devices with MTE, so 8th-gen Pixels and up would be able to 
reproduce it without the kernel patch

Run the module directly:

```sh
atest GosCompatWifiScanTimeoutTests
```
