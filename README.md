# Android Stream Viewer

Turn your Android device into a network camera viewer with go2rtc integration.

## Features

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

- Amazon Fire tablets
- Android TV devices
- Amazon Echo Show (10, 15, etc.)
- Any Android device with Lollipop 5.0+

## Building from Source

./gradlew assembleRelease

## License

MIT
