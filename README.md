# Hubitat Driver for ThirdReality Devices (Enhanced Fork)

This is an enhanced fork of the official [ThirdReality Hubitat driver repository](https://github.com/thirdreality/hubitat). It includes improvements and additional features not available in the original version — most notably, **dew point calculation** for supported sensors.

> ⚠️ This fork is community-maintained by [@krozgrov](https://github.com/krozgrov) and is not affiliated with ThirdReality, Inc.

---

## 📦 Supported Devices

| Device                              | Model           | Capabilities                               |
|-------------------------------------|-----------------|--------------------------------------------|
| **ThirdReality Temp/Humidity Sensor** | 3RHT18BZ (assumed) | Temperature, Humidity, Battery, **Dew Point** |

> 🧊 **Note:** Dew point is calculated **only** for devices that report both temperature and humidity.

---

## 🌡️ Dew Point Calculation

For compatible **environmental sensors** (e.g., 3RHT18BZ), this driver adds support for **dew point** as a calculated attribute.

- **Formula used:** Magnus approximation (accurate for indoor climate ranges)
- **Attribute exposed:** `dewPoint` (°C or °F based on Hubitat location settings)
- Enables smarter rules and dashboards for:
  - Humidity control
  - Mold risk reduction
  - HVAC optimization

---

## 🔧 Installation Instructions

### Manual Installation

1. Open your Hubitat admin UI.
2. Navigate to **Drivers Code** → **New Driver**.
3. Copy the relevant `.groovy` file(s) from this repo.
4. Paste and save each one as needed.

Repeat this process for each device type you plan to use (e.g., plug, sensor, switch).

---

### Assigning the Driver

1. Go to the **Devices** page in Hubitat.
2. Select the ThirdReality device you'd like to configure.
3. In the *Type* dropdown, choose the appropriate "ThirdReality..." driver.
4. Click **Save Device**.
5. Then click **Configure** (this initializes the device properly).

---

## 🚀 Features

- Native Zigbee 3.0 support
- Dew point calculation for temp/humidity sensors
- Battery level reporting
- Power metering support (where applicable)
- Reliable state syncing and attribute publishing
- Debug and descriptive logging options

---

## 🧪 Debugging and Logging

- **Debug logging** can be enabled via the device preferences.
- Logs automatically turn off after 30 minutes to reduce noise.
- **Descriptive logs** help confirm normal operation and remain enabled until disabled manually.

---

## 🤝 Credits

- **Original driver source**: [ThirdReality Hubitat GitHub](https://github.com/thirdreality/hubitat)
- Enhancements and ongoing maintenance by [@krozgrov](https://github.com/krozgrov)

---

## 📄 License

This repository inherits the [MIT License](LICENSE) from the upstream project. You are free to use, modify, and distribute with attribution.

---

## 💬 Feedback & Contributions

Issues, pull requests, and testing feedback are welcome!

- Open an [Issue](https://github.com/krozgrov/hubitat-thirdreality-driver/issues)
- Submit a [Pull Request](https://github.com/krozgrov/hubitat-thirdreality-driver/pulls)

Let’s make Hubitat + ThirdReality devices better together!
