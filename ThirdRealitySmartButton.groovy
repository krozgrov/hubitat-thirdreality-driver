metadata {
    definition(name: "ThirdReality Smart Button", namespace: "smartthings", author: "SmartThings", runLocally: true, minHubCoreVersion: '000.022.0000', executeCommandsLocally: false, mnmn: "SmartThings", vid: "SmartThings-smartthings-SmartSense_Button", ocfDeviceType: "x.com.st.d.remotecontroller") {
        capability "Configuration"
        capability "Battery"
        capability "Refresh"
        capability "Health Check"
        capability "Sensor"

		fingerprint inClusters: "0000,0001,0012", outClusters: "0006,0008,0019", manufacturer: "Third Reality, Inc", model: "3RSB22BZ"
    }
}

def installed() {
    sendEvent(name: "supportedButtonValues", value: ["pushed","held","double"].encodeAsJSON(), displayed: false)
    sendEvent(name: "numberOfButtons", value: 1, displayed: false)
    sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], displayed: false)
}

private List<Map> collectAttributes(Map descMap) {
    List<Map> descMaps = new ArrayList<Map>()

    descMaps.add(descMap)

    if (descMap.additionalAttrs) {
        descMaps.addAll(descMap.additionalAttrs)
    }

    return  descMaps
}

def parse(String description) {
    log.debug "description: $description"

    Map map = zigbee.getEvent(description)

	if (!map) {
	    Map descMap = zigbee.parseDescriptionAsMap(description)
		if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
		    List<Map> descMaps = collectAttributes(descMap)
			def battMap = descMaps.find { it.attrInt == 0x0020 }
			if (battMap) {
                map = getBatteryResult(Integer.parseInt(battMap.value, 16))
            }
		} else if ( descMap.clusterInt == 0x0012 ) {
		    map = translateMultiStatus(description)
		}
	}

    log.debug "Parse returned $map"
    def result = map ? createEvent(map) : [:]

    return result
}

private Map translateMultiStatus(String description) { 
    def descMap = zigbee.parseDescriptionAsMap(description)
	log.debug "descMap.value is : ${descMap.value}"
    
    if (descMap.clusterInt == 0x0012 && descMap.value == "0002" ) {
        return getButtonResult('double')
    } else if (descMap.clusterInt == 0x0012 && descMap.value == "0001" ) {
        return getButtonResult('pushed')
    } else if (descMap.clusterInt == 0x0012 && descMap.value == "0000"){
        return getButtonResult('held')
    } else {}
}

private Map getBatteryResult(rawValue) {
    log.debug "Battery rawValue = ${rawValue}"
    def linkText = getLinkText(device)

    def result = [:]

    def volts = rawValue / 10

    if (!(rawValue == 0 || rawValue == 255)) {
        result.name = 'battery'
        result.translatable = true
        result.descriptionText = "${ device.displayName } battery was ${ value }%"
		
		def minVolts = 2.1
		def maxVolts = 3.0
		def pct = (volts - minVolts) / (maxVolts - minVolts)
        def roundedPct = Math.round(pct * 100)
		if (roundedPct <= 0)
            roundedPct = 1
        result.value = Math.min(100, roundedPct)
	}

    return result
}

private Map getBatteryPercentageResult(rawValue) {
    log.debug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.descriptionText = "{{ device.displayName }} battery was {{ value }}%"
        result.value = Math.round(rawValue / 2)
    }

    return result
}

private Map getButtonResult(value) {
    def descriptionText
    if (value == "pushed")
        descriptionText = "${ device.displayName } was pushed"
    else if (value == "held")
        descriptionText = "${ device.displayName } was held"
    else
        descriptionText = "${ device.displayName } was pushed twice"
    return [
            name           : 'button',
            value          : value,
            descriptionText: descriptionText,
            translatable   : true,
            isStateChange  : true,
            data           : [buttonNumber: 1]
    ]
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
    zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS)
}

def refresh() {
    log.debug "Refreshing Values"
    def refreshCmds = []
	
	refreshCmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020)
	
    refreshCmds += zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
        zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS) +
        zigbee.enrollResponse()

    return refreshCmds
}

def configure() {
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
    // enrolls with default periodic reporting until newer 5 min interval is confirmed
    // Sets up low battery threshold reporting
    sendEvent(name: "DeviceWatch-Enroll", displayed: false, value: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, scheme: "TRACKED", checkInterval: 2 * 60 * 60 + 1 * 60, lowBatteryThresholds: [15, 7, 3], offlinePingable: "1"].encodeAsJSON())

    log.debug "Configuring Reporting"
    def configCmds = []
	configCmds += zigbee.batteryConfig()
    configCmds += zigbee.temperatureConfig(30, 300)

    return refresh() + configCmds + refresh() // send refresh cmds as part of config
}
