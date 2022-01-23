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
 *  Date: 2022-01-18    v1.1 MQTT reconnection functionality, daily/weekly/monthly/yearly water volumes
 *  Date: 2022-01-23    v1.2 Fixed issue with water volumes, added command for submission of rain data to gateway, changed child device logging
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
/*1.1*/ attribute "dailyVolume","number"
/*1.1*/ attribute "weeklyVolume","number"
/*1.1*/ attribute "monthlyVolume","number"
/*1.1*/ attribute "yearlyVolume","number"
/*1.2*/ attribute "rainSkipNotification","string"         // timestamp of the latest skipped watering

        
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

        // update current values in hubitat
/*1.2*/ command "setVolumes", [[name: "daily", type: "NUMBER"], [name: "weekly", type: "NUMBER"], [name: "monthly", type: "NUMBER"], , [name: "yearly", type: "NUMBER"]]
    
    }
    
    preferences {
        input(name: "autoClose", type: "number", title: "Seconds to activate watering on open", required: false, defaultValue: 30);
        input(name: "debug", type: "enum", title: "Print to debug log", options: ["yes","no"], required: true, defaultValue: "no");
    }

}
   
@Field final String devicePrefix = "LINKTAP_MQTT_";
@Field final Integer prefixLength = devicePrefix.size();


def close(){
    parent.logDebug("Close()");
    parent.sendCommand("stop water",[device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength)]);     
}

def open(){
    Integer param=autoClose as Integer;
    parent.logDebug("Open() command with autoclose: ${param}");
    parent.sendCommand("start water",[device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), param]);
}

def timedStart(duration) {
    parent.logDebug("timedStart() command with duration: ${duration}");
    parent.sendCommand("start water",[device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), duration as Integer]);
}

def dismissAlert(alertType) {
    parent.logDebug("dismissAlert()");
    parent.sendCommand("dismiss alert", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), alertType]);
}

def instantWateringPlan(timestamp, duration) {
    Random r=new Random();
    parent.logDebug("instantWateringPlan with timestamp $timestamp and duration $duration}");
    parent.sendCommand("set plan", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), r.nextInt(10000), 1, 0,0, timestamp, duration as Integer]);
}

def deleteWateringPlan() {
    parent.logDebug("deleteWateringPlan()");
    parent.sendCommand("delete plan", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength)]);
}

def childLock(lockType) {
    parent.logDebug("childLock() with input $lockType");
    parent.sendCommand("child lock", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), lockType]);
}

def enableAlert(alertType) {
    parent.logDebug("enableAlert() with input $alertType");
    parent.sendCommand("enable alert", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), alertType]);
}

def disableAlert(alertType) {
    parent.logDebug("disableAlert() with input $alertType");
    parent.sendCommand("disable alert", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength), alertType]);
}

def fetchGatewayTime() {
    parent.logDebug("fetchGatewayTime()");
    parent.sendCommand("fetch time", [device.currentValue("gatewayId") as String]);
}

def testWireless() {
    parent.logDebug("testWireless()");
    parent.sendCommand("test wireless", [device.currentValue("gatewayId") as String, device.getDeviceNetworkId().substring(prefixLength)]);
}

/* 1.1 */
private resetVolumes() {
    Date curDate=new Date();
    Integer day = curDate.date;
    Integer month = curDate[Calendar.DAY_OF_MONTH];
    Integer weekday = curDate[Calendar.DAY_OF_WEEK];
    
    parent.logDebug("resetVolumes()");
    parent.logTrace("day=$day, month=$month, weekday=$weekday");
    
    sendEvent(name: "dailyVolume", value: 0);
        
    if (weekday==1) sendEvent(name: "weeklyVolume", value: 0);
    if (day==1) sendEvent(name: "monthlyVolume", value: 0);
    if ((day==1) && (month==1)) sendEvent(name: "yearlyVolume", value: 0);
    
}

/* 1.1, updated 1.2 */
public addVolume(volume) {
    
    parent.logDebug("addVolume()");
    
    daily=(device.currentValue("dailyVolume")?:0)+volume;
    weekly=(device.currentValue("weeklyVolume")?:0)+volume;
    monthly=(device.currentValue("monthlyVolume")?:0)+volume;
    yearly=(device.currentValue("yearlyVolume")?:0)+volume;
    
    setVolumes(daily, weekly, monthly, yearly);
   
}

/* 1.2 */
private setVolumes(daily=null, weekly=null, monthly=null, yearly=null) {

    parent.logDebug("setVolumes()");
    
    (daily!=null)?sendEvent(name: "dailyVolume", value: daily):null;
    (weekly!=null)?sendEvent(name: "weeklyVolume", value: weekly):null;
    (monthly!=null)?sendEvent(name: "monthlyVolume", value: monthly):null;
    (yearly!=null)?sendEvent(name: "yearlyVolume", value: yearly):null;

}

private updated() {
    schedule("0 0 0 * * ?","resetVolumes");   
}

private installed() {
    updated();
}
