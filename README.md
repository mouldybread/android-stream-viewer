<p align="center">
  <img src="app/src/main/assets/logo.out.png" alt="Project Logo" width="256" />
</p>

# Android Stream Viewer

Turn your Android device into a network camera viewer with go2rtc integration and a HTTP api for home assistant integration.

> [!NOTE]
> I am *not* a programmer. This was developed with the help of AI. I use my displays to show cctv and wanted an automation friendly way to show video streams from go2rtc on my Android TV based devices. Do not expect much if anything in the way of support or features. It is however trivial to fork the code and adjust it should it not fit your usecase. It is released here, as is, in the hopes that it will help or inspire others.

> [!CAUTION]
> This application has **NO built-in authentication or encryption**. Much like go2rtc itself the web server runs on port 9090 with **unrestricted access** to anyone who can reach the device on your network.
> 
> ❌ **DO NOT** expose this app directly to the internet  
> ❌ **DO NOT** port forward 9090 to the internet  
> ❌ **DO NOT** use on untrusted networks (public WiFi, etc.)  
> ❌ **DO NOT** assume any built-in security exists  


## Features

- Automatically detects and indexs cameras from go2rtc
- View multiple camera streams from go2rtc
- WebRTC & MSE protocol support
- Camera tour mode (cycle through cameras)
- Burn-in protection (for displays)
- Web configuration interface
- Home Assistant integration
- NEW: Dynamic camera list API

## API Endpoints

### Camera Management
- GET /api/cameras - Get all cameras (full JSON)
- GET /api/camera-names - Get camera names as comma-separated list
- POST /api/cameras - Save cameras
- POST /api/camera/{id}/toggle - Toggle camera enabled/disabled

### Streaming
- POST /api/config - Configure stream
- GET /api/status - Get current stream status

### Tour
- POST /api/tour/start - Start camera tour
- POST /api/tour/stop - Stop camera tour
- GET /api/tour/status - Get tour status

### Other
- POST /api/discover - Discover cameras from go2rtc
- GET /api/logs - Get server logs
- GET /api/burn-in/status - Get burn-in protection status
- POST /api/burn-in/toggle - Toggle burn-in protection

## Home Assistant Integration

See home-assistant/README.md for setup instructions.

### Quick Start

1. Add REST sensor (rest.yaml):

- resource: http://<DEVICE_IP>:9090/api/camera-names
  scan_interval: 60
  sensor:
    - name: "Stream Viewer Camera Names"
      value_template: "{{ value }}"

2. Camera dropdown automatically populates!

## Installation

1. Download the APK from Releases
2. Enable "Install from Unknown Sources" on your Android device
3. Install APK via ADB, file manager, or browser
4. Configure go2rtc server URL in web interface

## Compatible Devices

Tested on an Amazon Echo Show 5 & CCwGTV 4K

## Building from Source

./gradlew assembleRelease
