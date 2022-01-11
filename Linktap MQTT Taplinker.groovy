/**
 *  Copyright 2022 Pedro Andrade
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *	Linktap Driver
 *
 * Author: Pedro Andrade
 *
 *	Updates:
 *	Date: 2022-01-10	v1.0 Initial release
 */

import java.text.DecimalFormat
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.util.Random
import groovy.transform.Field

metadata {
	definition (name: "Linktap MQTT Taplinker", namespace: "HE_pfta", author: "Pedro Andrade") {
    	capability "Battery"
        capability "LiquidFlowRate"
	    capability "Valve"
        attribute "gatewayId", "string"
        attribute "signalStrength","number"
        attribute "connectionStatus","boolean"
        attribute "flowMeterStatus","boolean"
        attribute "wateringSessionVolume","number"
        attribute "wateringSessionDuration","number"
        attribute "wateringSessionRemaining","number"
        attribute "alertFell", "boolean"
        attribute "alertBroken", "boolean"
        attribute "alertClogged", "boolean"
        attribute "alertCutoff", "boolean"
        attribute "alertLeak", "boolean"
        attribute "manualMode","boolean"
        attribute "planMode","number"
        attribute "childLock","string"
        
        // start watering with parametric duration
        command "timedStart", [[name: "Duration*", type: "NUMBER", description: "Watering session duration in seconds (for G1/G2 must be a multiple of 60, for G1s/G2s any number between 3 and 86399)", constraints: ["NUMBER"]]]
        
        // clear alert of the specific type
        command "dismissAlert", [[name: "alert", type: "ENUM", constraints: ["all types", "fell", "broken", "cutoff", "leak", "clogged"]]]
        
        // set an instant watering plan for a specific data/time and duration
        command "instantWateringPlan",[[name: "Start-yyyyMMddHHmmss*", type: "STRING", description: "Date and time for watering start in format yyyyMMddHHmmss"],[name: "Duration", type: "NUMBER", description: "Watering session duration in seconds (for G1/G2 must be a multiple of 60, for G1s/G2s any number between 3 and 86399)", constraints: ["NUMBER"]]]
        
        // delete previously set watering plan
        command "deleteWateringPlan"
        
        // set the behaviour of the physical button on the device
        command "childLock",[[name: "lockType", type: "ENUM", constraints: ["unlocked", "partially locked", "completely locked"]]]
        
        // enable alerts of specific type
        command "enableAlert", [[name: "alert", type: "ENUM", constraints: ["all types", "fell", "broken", "cutoff", "leak", "clogged"]]]
        
        // disable alerts of specific type
        command "disableAlert", [[name: "alert", type: "ENUM", constraints: ["all types", "fell", "broken", "cutoff", "leak", "clogged"]]]
        
        // print out to log the date/time currently set on the gateway
        command "fetchGatewayTime" // results will only print to log
        
        // execute and print out to log wireless test between device and gateway
        command "testWireless"
    }
    
    preferences {
        input(name: "autoClose", type: "number", title: "Seconds to activate watering on open", required: false, defaultValue: 30);
        input(name: "debug", type: "enum", title: "Print to debug log", options: ["yes","no"], required: true, defaultValue: "no");
    }

}
   
@Field final String devicePrefix = "LINKTAP_MQTT_";
@Field final Integer prefixLength = devicePrefix.size();


def close(){
    (debug=="yes")?log.debug("Close()"):null;
    parent.sendCommand("stop water",[device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength)]);     
}

def open(){
    Integer param=autoClose as Integer;
    (debug=="yes")?log.debug("Open() command with autoclose: ${param}"):null;
    parent.sendCommand("start water",[device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), param]);
}

def timedStart(duration) {
    (debug=="yes")?log.debug("timedStart() command with duration: ${duration}"):null;
    parent.sendCommand("start water",[device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), duration as Integer]);
}

def dismissAlert(alertType) {
    (debug=="yes")?log.debug("dismissAlert()"):null;
    parent.sendCommand("dismiss alert", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), alertType]);
}

def instantWateringPlan(timestamp, duration) {
    Random r=new Random();
    (debug=="yes")?log.debug("instantWateringPlan with timestamp $timestamp and duration $duration}"):null;
    parent.sendCommand("set plan", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), r.nextInt(10000), 1, 0,0, timestamp, duration as Integer]);
}

def deleteWateringPlan() {
    (debug=="yes")?log.debug("deleteWateringPlan()"):null;
    parent.sendCommand("delete plan", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength)]);
}

def childLock(lockType) {
    (debug=="yes")?log.debug("childLock() with input $lockType"):null;
    parent.sendCommand("child lock", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), lockType]);
}

def enableAlert(alertType) {
    (debug=="yes")?log.debug("enableAlert() with input $alertType"):null;
    parent.sendCommand("enable alert", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), alertType]);
}

def disableAlert(alertType) {
    (debug=="yes")?log.debug("disableAlert() with input $alertType"):null;
    parent.sendCommand("disable alert", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), alertType]);
}

def fetchGatewayTime() {
    (debug=="yes")?log.debug("fetchGatewayTime()"):null;
    parent.sendCommand("fetch time", [device.currentValue("gatewayId") as String]);
}

def testWireless() {
    (debug=="yes")?log.debug("testWireless()"):null;
    parent.sendCommand("test wireless", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength)]);
}
