import hubitat.zigbee.zcl.DataType

metadata {
    definition(
        name: "ThirdReality Temp/Humidity Sensor",
        namespace: "hubitat",
        author: "krozgrov",
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

        // Third Reality fingerprint
        fingerprint profileId: "0104",
                   deviceId: "0302",
                   inClusters: "0000,0001,0402,0405",
                   outClusters: "0019",
                   manufacturer: "Third Reality, Inc",
                   model: "3RTS20BZ",
                   deviceJoinName: "ThirdReality Thermal & Humidity Sensor"
    }

    preferences {
        input "tempOffset", "number",
              title: "Temperature offset",
              description: "Select how many degrees to adjust the temperature.",
              range: "-100..100",
              displayDuringSetup: false

        input "humidityOffset", "number",
              title: "Humidity offset",
              description: "Enter a percentage to adjust the humidity.",
              range: "-100..100",
              displayDuringSetup: false

        // ─── NEW: Temperature Reporting Change Threshold (°C) ──────────
        input "tempChangeThreshold", "enum",
              title: "Temperature Reporting Threshold (°C)",
              options: [
                  "0.5" : "0.5 °C",
                  "1"   : "1.0 °C",
                  "2"   : "2.0 °C",
                  "5"   : "5.0 °C"
              ],
              defaultValue: "0.5",
              description: "Only report when temperature changes by at least this amount."

        // ─── NEW: Humidity Reporting Change Threshold (%RH) ───────────
        input "humidityChangeThreshold", "enum",
              title: "Humidity Reporting Threshold (%RH)",
              options: [
                  "1"  : "1 %",
                  "2"  : "2 %",
                  "5"  : "5 %",
                  "10" : "10 %"
              ],
              defaultValue: "1",
              description: "Only report when humidity changes by at least this amount."
    }
}

def parse(String description) {
    log.debug "description: $description"

    // getEvent will handle temperature and humidity
    Map map = zigbee.getEvent(description)

    if (!map) {
        Map descMap = zigbee.parseDescriptionAsMap(description)
        if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
            if (descMap.attrInt == 0x0021) {
                map = getBatteryPercentageResult(Integer.parseInt(descMap.value, 16))
            } else {
                map = getBatteryResult(Integer.parseInt(descMap.value, 16))
            }
        } else if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
            if (descMap.data[0] == "00") {
                log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
                sendEvent(
                    name: "checkInterval",
                    value: 60 * 12,
                    displayed: false,
                    data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]
                )
            } else {
                log.warn "TEMP REPORTING CONFIG FAILED - error code: ${descMap.data[0]}"
            }
        } else if (descMap?.clusterInt == 0x0405 && descMap.attrInt != 0x07) {
            def raw = Integer.parseInt(descMap.value, 16)
            humidityEvent(raw / 100.0)
        }
    } else if (map.name == "temperature") {
        if (tempOffset) {
            map.value = new BigDecimal((map.value as float) + (tempOffset as float))
                            .setScale(1, BigDecimal.ROUND_HALF_UP)
        }
        map.descriptionText = temperatureScale == 'C' ?
            '{{ device.displayName }} was {{ value }}°C' :
            '{{ device.displayName }} was {{ value }}°F'
        map.translatable = true
    } else if (map.name == "humidity") {
        if (humidityOffset) {
            map.value = (int) map.value + (int) humidityOffset
        }
    }

    log.debug "Parse returned $map"
    return map ? createEvent(map) : [:]
}

def humidityEvent(humidity) {
    def map = [:]
    map.name = "humidity"
    map.value = humidity as int
    map.unit = "% RH"
    map.isStateChange = true
    if (settings?.txtEnable) {
        log.info "${device.displayName} ${map.name} is ${Math.round((humidity) * 10) / 10} ${map.unit}"
    }
    sendEvent(map)
}

def getBatteryPercentageResult(rawValue) {
    log.debug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.value = Math.round(rawValue / 2)
        result.descriptionText = "${device.displayName} battery was ${result.value}%"
    }

    return result
}

private Map getBatteryResult(rawValue) {
    log.debug 'Battery'
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

/**
 * PING is used by Device-Watch in attempt to reach the Device
 */
def ping() {
    return zigbee.readAttribute(0x0001, 0x0020) // Read the Battery Level
}

def refresh() {
    log.debug "refresh temperature, humidity, and battery"

    return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +
           zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
           zigbee.readAttribute(0x0405, 0x0000)
}

def configure() {
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
    sendEvent(
        name: "checkInterval",
        value: 2 * 60 * 60 + 1 * 60,
        displayed: false,
        data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]
    )

    log.debug "Configuring Reporting and Bindings with thresholds: " +
              "tempChange=${settings.tempChangeThreshold}°C, " +
              "humidityChange=${settings.humidityChangeThreshold}%"

    // Compute raw “reportable change” values; use fallbacks if settings are null
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
        0x1
    )

    return refresh() + humidityConfig + temperatureConfig + batteryConfig
}