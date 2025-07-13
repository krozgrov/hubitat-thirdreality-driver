import hubitat.zigbee.zcl.DataType

metadata {
    definition(
        name: "ThirdReality Temp/Humidity Sensor w/ Dew Point",
        namespace: "krozgrov",
        author: "thirdreality",
        runLocally: true,
        minHubCoreVersion: '000.017.0012',
        executeCommandsLocally: false,
        ocfDeviceType: "oic.d.thermostat"
    ) {
        capability "Configuration"
        capability "Battery"
        capability "Refresh"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Health Check"
        capability "Sensor"

        // Ensure dewPoint persists under Current States
        attribute "dewPoint", "number"

        fingerprint profileId: "0104", deviceId: "0302",
                   inClusters: "0000,0001,0402,0405",
                   outClusters: "0019",
                   manufacturer: "Third Reality, Inc",
                   model: "3RTS20BZ",
                   deviceJoinName: "ThirdReality Thermal & Humidity Sensor"
    }

    preferences {
        input name: "tempOffset",
              type: "number",
              title: "Temperature offset (°C)",
              description: "Select how many degrees to adjust the temperature (+/-).",
              range: "-100..100",
              displayDuringSetup: false

        input name: "humidityOffset",
              type: "number",
              title: "Humidity offset (%RH)",
              description: "Enter a percentage to adjust the humidity (+/-).",
              range: "-100..100",
              displayDuringSetup: false

        input name: "tempChangeThreshold",
              type: "enum",
              title: "Temperature Reporting Threshold (°C)",
              options: [
                  "0.5" : "0.5 °C",
                  "1"   : "1.0 °C",
                  "2"   : "2.0 °C",
                  "5"   : "5.0 °C"
              ],
              defaultValue: "0.5",
              description: "Only report when temperature changes by at least this amount."

        input name: "humidityChangeThreshold",
              type: "enum",
              title: "Humidity Reporting Threshold (%RH)",
              options: [
                  "1"  : "1 %",
                  "2"  : "2 %",
                  "5"  : "5 %",
                  "10" : "10 %"
              ],
              defaultValue: "1",
              description: "Only report when humidity changes by at least this amount."

        input name: "enableDewPointReporting",
              type: "bool",
              title: "Enable Dew Point Calculation",
              defaultValue: true,
              description: "Calculate & send dew point whenever T or H changes."
    }
}

////////////////////////////////////////////////////////////////
// parse(String description)
// ─────────────────────────────────────────────────────────────
// 1) Short‐circuit “catchall” frames to reduce log clutter.
// 2) Only meaningful T / H / Battery / Config‐Response frames get logged.
// 3) Then run the normal logic to update temperature, humidity, battery, dewPoint.
////////////////////////////////////////////////////////////////
def parse(String description) {
    // ─── 1) QUICKLY FILTER OUT CATCHALL / BASIC FRAMES ───────────────────
    Map quick = zigbee.parseDescriptionAsMap(description)
    if (quick?.clusterInt == 0x0000) {
        // cluster 0x0000 is just the Zigbee Basic (catchall) profile—ignore.
        return [:]
    }
    // If it’s not a read‐attribute on 0x0402 (Temp), 0x0405 (Humidity), or
    // 0x0001 (Battery), and commandInt is null → it’s an unhandled “catchall” → ignore.
    if (quick && quick.commandInt == null &&
        (quick.clusterInt != zigbee.TEMPERATURE_MEASUREMENT_CLUSTER) &&
        (quick.clusterInt != 0x0405) &&
        (quick.clusterInt != 0x0001)
    ) {
        return [:]
    }

    // ─── 2) NOW LOG only meaningful frames ───────────────────────────────
    log.debug "Raw description: ${description}"

    // 3) Let Hubitat attempt to parse a Temperature event
    Map map = zigbee.getEvent(description)

    // 4) If getEvent returned null, manually parse Humidity or Battery or Config Response
    if (!map) {
        Map descMap = zigbee.parseDescriptionAsMap(description)

        // ─── HUMIDITY (Cluster 0x0405, Attr=0x0000) ───────────────────────
        if (descMap.clusterInt == 0x0405 && descMap.attrInt == 0x0000 && descMap.value) {
            // Raw humidity is a UINT16 (value / 100)
            def rawHum = Integer.parseInt(descMap.value, 16)
            def humPct = (rawHum / 100.0) as Double
            log.debug "Parsed raw humidity: ${rawHum} → ${humPct}%"

            map = [
                name:  "humidity",
                value: humPct as Integer,
                unit:  "% RH"
            ]
        }
        // ─── TEMPERATURE CONFIG RESPONSE (Cluster=0x0402, Cmd=0x07) ───────
        else if (descMap.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER &&
                 descMap.commandInt == 0x07) {
            if (descMap.data[0] == "00") {
                log.debug "TEMP REPORTING CONFIG SUCCESS: ${descMap}"
                sendEvent(
                    name: "checkInterval",
                    value: 60 * 12,
                    displayed: false,
                    data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]
                )
            } else {
                log.warn "TEMP REPORTING CONFIG FAILED - error code: ${descMap.data[0]}"
            }
        }
        // ─── BATTERY (Cluster=0x0001, Attr != 0x07) ────────────────────────
        else if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap.value) {
            if (descMap.attrInt == 0x0021) {
                map = getBatteryPercentageResult(Integer.parseInt(descMap.value, 16))
            } else {
                map = getBatteryResult(Integer.parseInt(descMap.value, 16))
            }
        }
    }

    // 5) If map now contains a Temperature / Humidity / Battery event, process it
    if (map) {
        // ─── TEMPERATURE EVENTS ────────────────────────────────────────────
        if (map.name == "temperature") {
            // map.value is already in Hub’s displayed units (C or F)
            Double reportedTemp = map.value as Double

            // Convert back to Celsius so dew point math works correctly
            Double finalTempC = (temperatureScale == 'F') ?
                ((reportedTemp - 32.0) * 5.0 / 9.0) :  // °F → °C
                reportedTemp                            // already °C

            log.debug "Raw temperature (converted to °C): ${finalTempC} °C"

            // Apply any user offset (in °C)
            if (settings.tempOffset != null) {
                finalTempC = finalTempC + (settings.tempOffset as Double)
            }
            finalTempC = (finalTempC as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toDouble()
            log.debug "Adjusted temperature (after offset, °C): ${finalTempC} °C"

            // Put the correct display‐value back into map.value in Hub’s units
            if (temperatureScale == 'F') {
                Double displayF = (finalTempC * 9.0 / 5.0 + 32.0)
                displayF = (displayF as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toDouble()
                map.value = displayF
                map.unit = "°F"
                map.descriptionText = "${device.displayName} was ${map.value}°F"
            } else {
                map.value = finalTempC
                map.unit = "°C"
                map.descriptionText = "${device.displayName} was ${map.value}°C"
            }
            map.translatable = true

            // Save the true Celsius temperature for dew point math
            state.lastTempC = finalTempC as Double
            log.debug "state.lastTempC set to ${state.lastTempC} °C"
        }
        // ─── HUMIDITY EVENTS ───────────────────────────────────────────────
        else if (map.name == "humidity") {
            log.debug "Raw humidity event value: ${map.value}% RH"

            // Apply any user offset
            if (settings.humidityOffset != null) {
                map.value = ((map.value as Integer) + (settings.humidityOffset as Integer)) as Integer
            }
            log.debug "Adjusted humidity (after offset): ${map.value}% RH"

            // Save the final humidity (integer percent) for dew point math
            state.lastHumidityPct = map.value as Integer
            log.debug "state.lastHumidityPct set to ${state.lastHumidityPct}%"
        }
        // ─── BATTERY EVENTS ───────────────────────────────────────────────
        else if (map.name == "battery") {
            log.debug "Battery event: ${map.value}%"
            // no dew point logic here
        }

        // 6) Create the Temperature / Humidity / Battery event
        def evt = createEvent(map)

        // 7) Recalculate & send dewPoint if T or H changed
        if (settings.enableDewPointReporting && (map.name == "temperature" || map.name == "humidity")) {
            Double tC  = state.lastTempC  as Double
            Double hPct = state.lastHumidityPct as Double

            if (tC != null && hPct != null) {
                // Compute dew point in °C
                def dpC = calculateDewPoint(tC, hPct)
                def dpRoundedC = (dpC as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toDouble()
                log.debug "Calculated dewPoint (°C): ${dpRoundedC} °C"

                // Convert to °F if Hub is in Fahrenheit mode
                Double dpDisplay = dpRoundedC
                if (temperatureScale == "F") {
                    dpDisplay = (dpRoundedC * 9.0 / 5.0 + 32.0)
                    dpDisplay = (dpDisplay as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toDouble()
                    log.debug "Converted dewPoint to °F: ${dpDisplay} °F"
                }

                // Send the custom attribute “dewPoint”
                sendEvent(
                    name: "dewPoint",
                    value: dpDisplay,
                    unit: (temperatureScale == "C" ? "°C" : "°F"),
                    descriptionText: "${device.displayName} dew point is ${dpDisplay}${(temperatureScale=='C'?'°C':'°F')}"
                )
            } else {
                log.debug "Skipping dewPoint calc: T or H missing (T=${tC}, H=${hPct})"
            }
        }

        return evt
    }

    // 8) No map produced, ignore
    return [:]
}

/////////////////////////////////////////////////////////////////////////
// calculateDewPoint(Double tempC, Double humidityPct)
// ──────────────────────────────────────────────────────────────────────
// Uses Magnus‐Tetens approximation to compute dew point in °C.
// If humidityPct ≤ 0, returns a very low value (–100 °C) to avoid ln(0).
/////////////////////////////////////////////////////////////////////////
private Double calculateDewPoint(Double tempC, Double humidityPct) {
    final double B = 17.27
    final double C = 237.7

    if (humidityPct <= 0.0) {
        return -100.0
    }
    double alpha = (B * tempC / (C + tempC)) + Math.log(humidityPct / 100.0)
    double dewPointC = (C * alpha) / (B - alpha)
    return dewPointC
}

/////////////////////////////////////////////////////////////////////////
// getBatteryPercentageResult(Integer rawValue)
// ──────────────────────────────────────────────────────────────────────
// Parses raw battery percentage (0..200) → 0..100%.
/////////////////////////////////////////////////////////////////////////
def getBatteryPercentageResult(rawValue) {
    log.debug "Battery Percentage rawValue = ${rawValue} → ${rawValue / 2}%"
    def result = [:]
    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.value = Math.round(rawValue / 2)
        result.descriptionText = "${device.displayName} battery was ${result.value}%"
    }
    return result
}

/////////////////////////////////////////////////////////////////////////
// getBatteryResult(Integer rawValue)
// ──────────────────────────────────────────────────────────────────────
// Converts raw voltage (e.g., 21 → 2.1V) into a 0–100% battery percentage.
/////////////////////////////////////////////////////////////////////////
private Map getBatteryResult(rawValue) {
    log.debug 'Battery voltage rawValue = ' + rawValue
    def linkText = getLinkText(device)
    def result = [:]
    def volts = rawValue / 10
    if (!(rawValue == 0 || rawValue == 255)) {
        def minVolts = isFrientSensor() ? 2.3 : 2.1
        def maxVolts = 3.0
        def pct = (volts - minVolts) / (maxVolts - minVolts)
        def roundedPct = Math.round(pct * 100)
        if (roundedPct <= 0) roundedPct = 1
        result.value = Math.min(100, roundedPct)
        result.descriptionText = "${linkText} battery was ${result.value}%"
        result.name = 'battery'
    }
    return result
}

/////////////////////////////////////////////////////////////////////
// ping()
// ─────────────────────────────────────────────────────────────────
// Called by Device‐Watch to verify the device is alive. We read
// Battery Level (cluster 0x0001, attribute 0x0020).
/////////////////////////////////////////////////////////////////////
def ping() {
    return zigbee.readAttribute(0x0001, 0x0020)
}

/////////////////////////////////////////////////////////////////////
// refresh()
// ─────────────────────────────────────────────────────────────────
// Called when the user clicks “Refresh.” We read Battery, Temp, Humidity.
/////////////////////////////////////////////////////////////////////
def refresh() {
    log.debug "Refresh temperature, humidity, and battery"
    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
           zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
           zigbee.readAttribute(0x0405, 0x0000)
}

/////////////////////////////////////////////////////////////////////////
// configure()
// ─────────────────────────────────────────────────────────────────────
// Called on pairing or when user clicks “Configure.” We set up
// reporting intervals & thresholds for Humidity, Temperature, Battery.
/////////////////////////////////////////////////////////////////////////
def configure() {
    // Device‐Watch: allow 2 missed check‐ins + 1‐minute lag
    sendEvent(
        name: "checkInterval",
        value: 2 * 60 * 60 + 1 * 60,
        displayed: false,
        data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]
    )

    log.debug "Configuring Reporting & Bindings with thresholds: " +
              "tempChange=${settings.tempChangeThreshold}°C, " +
              "humidityChange=${settings.humidityChangeThreshold}%"

    // Compute raw “reportable change” values
    def tempThresholdC = (settings.tempChangeThreshold as BigDecimal) ?: 0.5
    def tempReportableChange = (tempThresholdC * 100).toInteger()  // e.g. 0.5°C → 50

    def humidityThresholdPct = (settings.humidityChangeThreshold as Integer) ?: 1
    def humidityReportableChange = (humidityThresholdPct * 100).toInteger()  // e.g. 1% → 100

    // 1) Humidity (Cluster 0x0405, MeasuredValue = 0x0000)
    def humidityConfig = zigbee.configureReporting(
        0x0405,
        0x0000,
        DataType.UINT16,
        60,
        600,
        humidityReportableChange
    )

    // 2) Temperature (Cluster 0x0402, MeasuredValue = 0x0000)
    def temperatureConfig = zigbee.configureReporting(
        zigbee.TEMPERATURE_MEASUREMENT_CLUSTER,
        0x0000,
        DataType.INT16,
        60,
        600,
        tempReportableChange
    )

    // 3) Battery (Cluster 0x0001, BatteryVoltage = 0x0020)
    def batteryConfig = zigbee.configureReporting(
        zigbee.POWER_CONFIGURATION_CLUSTER,
        0x0020,
        DataType.UINT8,
        30,
        21600,
        0x01
    )

    return refresh() + humidityConfig + temperatureConfig + batteryConfig
}
