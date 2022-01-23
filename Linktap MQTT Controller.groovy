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
 *	Linktap MQTT Driver
 *
 * Author: Pedro Andrade
 *
 *
 *	Updates:
 *	Date: 2022-01-10	v1.0 Initial release
 *  Date: 2022-01-12    v1.1 Added rain data management and MQTT reconnection functionality
 *  Date: 2022-01-23    v1.2 Fixed issue with water volumes, added command for submission of rain data to gateway, changed child device logging
 */

import groovy.transform.Field

metadata {
  
  definition(name: "Linktap MQTT Controller", namespace: "HE_pfta", author: "Pedro Andrade") {
/*1.1*/   capability "Actuator"                             // having a capability allows the device to be used for custom actions in Rule Machine
          attribute "MQTTConnectionStatus","string"        // Connected if hubitat/user has instructed driver to connect to MQTT broker
/*1.1*/   attribute "MQTTConnectionMessage","string"        // last message received from Hubitat MQTT client
/*1.1*/   attribute "MQTTLastMessageTimestamp","string"     // timestamp of last message
/*1.2*/   attribute "obsRainData","number"                  // observed rain volume
/*1.2*/   attribute "forRainData","number"                  // forecasted rain volume
/*1.2*/   attribute "rainDuration","number"                 // period for observation/forecast
/*1.2*/   attribute "rainUpdateTimestamp","number"          // timestamp of when rain data received

          command "MQTTConnect"
          command "MQTTDisconnect"
          command "registerEndDevice", [[name: "GatewayId*", type: "STRING"], [name: "DeviceId*", type: "STRING"]]
          command "deleteEndDevice", [[name: "GatewayId*", type: "STRING"], [name: "DeviceId*", type: "STRING"]]
          command "clearPendingDevices" // used to clear pending registration/deletion of devices in case something went wrong
/*1.2*/   command "updateRain", [[name: "gatewayId", type: "STRING"], [name: "obsRainData", type: "NUMBER"], [name: "forRainData", type: "NUMBER"], [name: "rainDuration", type: "NUMBER"]]

  }

  preferences {
            input(name: "brokerIP", type: "string", title: "Broker IP", description: "IP of the MQTT Broker", defaultValue: "", required: true);
            input(name: "brokerPort", type: "string", title: "Broker Port", description: "Port of the MQTT Broker", defaultValue: "1883", required: true);
            input(name: "username", type: "string", title: "Username", description: "Username for the MQTT Broker", defaultValue: "", required: false);
            input(name: "password", type: "string", title: "Password", description: "Password for the MQTT Broker", defaultValue: "", required: false);
            input(name: "logLevel", type: "enum", title: "Log Level", options: ["0","1","2","3","4"], defaultValue: "2");
/*1.1*/     input(name: "conCheck", type: "number", title: "Connection check interval", description: "How often to verify MQTT connection status and attempt to reconnect", defaultValue: 300);
/*1.1*/     input(name: "reconAttempts", type: "number", title: "Reconnection attempts", description: "How many times to try to reconnect (-1 is indefinite)", defaultValue: 3);
  }
}    


@Field final Map downCommands = [
     1: 'register device',
     2: 'delete device',
     4: 'set plan',
     5: 'delete plan',
     6: 'start water',
     7: 'stop water',
/*1.2*/     8: 'update rain',
     10: 'alert on/off',
     11: 'dismiss alert',
     12: 'child lock',
     14: 'fetch time',
     15: 'test wireless'];

@Field final Map waterModes = [
    0: 'none',
    1: 'instant',
    2: 'calendar',
    3: '7 day',
    4: 'odd-even',
    5: 'interval',
    6: 'month'];

@Field final Map retCodes = [
     0: 'Success',
     1: 'Message format error',
     2: 'CMD message not supported',
     3: 'Gateway ID not matched',
     4: 'End device ID error',
     5: 'End device ID not found',
     6: 'Gateway internal error',
     7: 'Conflict with watering plan'];
                
@Field final Map statusMessages = [
    'is_rf_linked': 'Connection status of the water timer with the Gateway',
    'is_flm_plugin': 'Connection status of the flow meter',
    'is_fall': 'Water timer fall alert status',
    'is_broken': 'Valve shut-down failure alert status',
    'is_cutoff': 'Water cut-off alert status',
    'is_leak': 'Unusually high flow alert status',
    'is_clog': 'Unusually low flow alert status'];

@Field final Map alertTypes = [
    'all types': 0,
    'fell': 1,
    'broken': 2,
    'cutoff': 3,
    'leak': 4,
    'clogged': 5];
                      
@Field final Map childLock = [0: 'unlocked', 1: 'partially locked', 2: 'completely locked'];

@Field final String devicePrefix = "LINKTAP_MQTT_";
@Field final Integer prefixLength = devicePrefix.size();


// ********************** MQTT Connectivity

def MQTTConnect() {

    logDebug("ENTERED MQTTConnect");

    if (MQTTisConnected()) {

        logWarning("MQTTConnect: Already connected");
    
    } else {
        
        logTrace("tcp://$brokerIP:$brokerPort, HE_LINKTAP_CLIENT, $username, $password");
        
        // connect to MQTT broker
        try {
            interfaces.mqtt.connect("tcp://$brokerIP:$brokerPort", "HE_LINKTAP_CLIENT", username, password);
            pauseExecution(1000);
            logInfo("MQTTConnect: ${MQTTisConnected()?"Connected to broker $brokerIP:$brokerPort":"Connection failed"}");
        
            if (MQTTisConnected()) {
                interfaces.mqtt.subscribe("/linktap/up_cmd/#",0); // topic to receive requests from the gateway
                interfaces.mqtt.subscribe("/linktap/down_cmd_ack/#",0); // topic to receive acknowledgements from gateway after sending of commands
                state.actualReconAttempts=0; /*1.1*/
            }
        } catch (org.eclipse.paho.client.mqttv3.MqttException e) {
            logError("MQTTConnect: Cannot connect to broker ($e)");
        } catch (Exception e) {
            logError("MQTTConnect: Unknown error ($e)");
        }

        if (device.currentValue("MQTTConnectionStatus")=="Disconnected") { /*1.1*/
            sendEvent(name: "MQTTConnectionStatus", value: "Connected");
        }
    
        if ((conCheck?:0)>0) runIn(conCheck,'checkMQTTConnection',null); // schedule connection status check
    
    }
    
    
    logDebug("END MQTTConnect");
}
 

def MQTTDisconnect() {
    
    logDebug("ENTERED MQTTDisconnect");
    
    if (!MQTTisConnected()) {
        logWarning("MQTTDisconnect: Not connected");
    } else {
        interfaces.mqtt.disconnect();
        logInfo("MQTTDisconnect: Disconnected from broker");
    }
    
    sendEvent(name: "MQTTConnectionStatus", value: "Disconnected");
    presence="not present";
    
    logDebug("END MQTTDisconnect");
}


def mqttClientStatus(String message) {
    
    logInfo("MQTT Client Status: $message")
    sendEvent(name: "MQTTConnectionMessage", value: message);    /*1.1*/
    sendEvent(name: "MQTTLastMessageTimestamp", value: (new Date()).format("yyyy-MM-dd HH:mm:ss"));    /*1.1*/
    
}


/*1.1*/
def checkMQTTConnection() {
    // MQTTConnectionStatus has the indication of the "intent" of the user, not necessarily the actual connection status, and is updated in:
    //    a) MQTTConnect to Connected
    //    b) MQTTDisconnect to Disconnected

    logDebug("ENTERED checkMQTTConnection ${state.actualReconAttempts} < $reconAttempts");
    
    if (device.currentValue("MQTTConnectionStatus")=="Connected") { // if device thinks connection should be active

        logDebug("MQTTConnectionStatus=${device.currentValue("MQTTConnectionStatus")} and MQTT client is actually ${MQTTisConnected()?'':'not '}connected");
        
        if (!MQTTisConnected())
            if (((state.actualReconAttempts<reconAttempts) || (reconAttempts==-1))) { // only attempt reconnect if have not reached max attempts
                logWarning("MQTT client is not connected while it should, attempting to reconnect");
                MQTTConnect();      // re-connect, which will also schedule next check
                if (!MQTTisConnected()) state.actualReconAttempts++;  // if failed, increase number of attempts. This state variable is re-set within MQTTConnect if connection successful
            } else {// give up on reconnection
                sendEvent(name: "MQTTConnectionStatus", value: "Disconnected");
                logWarning("Max reconnect attempts reached, will stop checking connection status");
            }
        else
            // schedule next check
            if ((conCheck?:0)>0) runIn(conCheck,'checkMQTTConnection',null);
    }
}


private boolean MQTTisConnected() { return interfaces.mqtt.isConnected() }


// ********************** Reused logging code from Ecowitt Wifi Gateway Hubitat Driver
Integer logGetLevel() {
  //
  // Get the log level as an Integer:
  //
  //   0) log only Errors
  //   1) log Errors and Warnings
  //   2) log Errors, Warnings and Info
  //   3) log Errors, Warnings, Info and Debug
  //   4) log Errors, Warnings, Info, Debug and Trace/diagnostic (everything)
  //
  // If the level is not yet set in the driver preferences, return a default of 2 (Info)
  // Declared public because it's being used by the child-devices as well
  //
  if (settings.logLevel != null) return (settings.logLevel.toInteger());
  return (2);
}

private void logError(String str) { log.error(str); }
private void logWarning(String str) { if (logGetLevel() > 0) log.warn(str); }
private void logInfo(String str) { if (logGetLevel() > 1) log.info(str); }
private void logDebug(String str) { if (logGetLevel() > 2) log.debug(str); }
private void logTrace(String str) { if (logGetLevel() > 3) log.trace(str); }
private void logData(Map data) {
  if (logGetLevel() > 3) {
    data.each {
      logTrace("$it.key = $it.value");
    }
  }
}

void installed() {
    sendEvent(name: "MQTTConnectionStatus", value: "Disconnected");
}

                  
                  
// ********************** Message Parsing
void parse(String message) {
    logDebug("ENTERED parse");

    Map parsedMessage=interfaces.mqtt.parseMessage(message);
    
    String stringTopic=parsedMessage["topic"];
    String stringMessage=parsedMessage["payload"];
    
    logTrace("TOPIC: $stringTopic");
    logTrace("MESSAGE: $stringMessage");
    
    String msgType=stringTopic.split("/")[2];
    
    String deviceId="";
    if (stringTopic.split("/").length>3) {
        deviceId=stringTopic.split("/")[3];
    };
    
    switch (msgType) {
        case "up_cmd":
            processUpCommand(deviceId, stringMessage);
        break;
        case "down_cmd_ack":
            processDownAck(deviceId, stringMessage);
        break;
    }
        
}

private processUpCommand(String deviceId, String stringMessage) {

    logDebug("ENTERED processUpCommand FOR DEVICE $deviceId");
    
    def slurper = new groovy.json.JsonSlurper();
    def result=slurper.parseText(stringMessage);

    Integer cmd=result.cmd;
    logTrace("processUpCommand: cmd:  $cmd");

    String gatewayId=result.gw_id?:null;
    
    if (gatewayId) {logTrace("Gateway: ${gatewayId}");}
    if (deviceId) {logTrace("Device: ${deviceId}");}
    
    switch (cmd) {
        case 0: // hello
            // check we have all devices for this gateway
            addOrUpdateDevices(result.end_dev, gatewayId);
            ackHandshake(gatewayId);
        break;
        case 3: // update device status
            updateDeviceStatus(deviceId, result.dev_stat);
        break;
        case 13: // sync gw time
            ackSyncTime(gatewayId);
        break;
        case 8: // fetch rainfall data
            fetchRainfallData(gatewayId); /* 1.2 */
        break;
        case 9: // skipped watering
            def devId=result.dev_id;
            rainSkipNotification(devId); /* 1.2 */
        break;
        default:
            logWarning("Unsupported command received from gateway $cmd");
        break;
    }
    
    logDebug("END processUpCommand");
}


private com.hubitat.app.ChildDeviceWrapper addOrUpdateDevices(ArrayList devices, String gatewayId) {

    logDebug("ENTERED addOrUpdateDevices FOR DEVICES $devices AND gateway $gatewayId");
    
    def tapLinker=null;
    devices.each {
        tapLinker=getChildDevice("LINKTAP_MQTT_$it");
        if (tapLinker==null) {
            logDebug("addOrUpdateDevices: could not find tapLinker device, should create");
            tapLinker=addChildDevice("HE_pfta", "Linktap MQTT Taplinker", "$devicePrefix$it", [name: it]);
            logInfo("addOrUpdateDevices: New taplinker $it found in gateway $gatewayId, created");
            // update basic attributes
            tapLinker.sendEvent(name: "gatewayId", value: gatewayId);                
         } else {
            // if exists, check if gatewayId is the same
            String setting=tapLinker.currentValue("gatewayId") as String;
            logDebug("addOrUpdateDevices: found existing tapLinker, current gw: $setting");
            if (setting!=gatewayId) {
                // different gateway Id, device may have been reassigned to a different gateway
                logInfo("addOrUpdateDevices: Taplinker $it with different gateway Id, will update to $gatewayId");
                tapLinker.sendEvent(name: "gatewayId", value: gatewayId);
            }
        }
    }
    return (tapLinker);
}


private updateDeviceStatus(deviceId, dev_stat) {
    def tapLinker=getChildDevice("$devicePrefix$deviceId");
    if (tapLinker) {
        tapLinker.sendEvent(name: "connectionStatus", value: dev_stat.is_rf_linked, descriptionText: statusMessages.getAt("is_rf_linked"));
        tapLinker.sendEvent(name: "flowMeterStatus", value: dev_stat.is_flm_plugin, descriptionText: statusMessages.getAt("is_flm_plugin"));
        tapLinker.sendEvent(name: "signalStrength", value: dev_stat.is_rf_linked?dev_stat.signal:0);
        tapLinker.sendEvent(name: "battery", value: dev_stat.battery);
/*1.2*/ if ((dev_stat.volume>0) && (dev_stat.is_final) && (!dev_stat.is_watering) && (tapLinker.currentValue("valve"))) {
            tapLinker.addVolume(dev_stat.volume);
        }
        tapLinker.sendEvent(name: "valve", value: dev_stat.is_watering);
        tapLinker.sendEvent(name: "rate", value: dev_stat.speed);
        tapLinker.sendEvent(name: "wateringSessionDuration", value: dev_stat.total_duration);
        tapLinker.sendEvent(name: "wateringSessionRemaining", value: dev_stat.remain_duration);
        tapLinker.sendEvent(name: "wateringSessionVolume", value: dev_stat.volume);
        tapLinker.sendEvent(name: "alertFell", value: dev_stat.is_fall, descriptionText: dev_stat.is_fall?statusMessages.getAt("is_fall"):"");
        tapLinker.sendEvent(name: "alertBroken", value: dev_stat.is_broken, descriptionText: dev_stat.is_broken?statusMessages.getAt("is_broken"):"");
        tapLinker.sendEvent(name: "alertCutoff", value: dev_stat.is_cutoff, descriptionText: dev_stat.is_cutoff?statusMessages.getAt("is_cutoff"):"");
        tapLinker.sendEvent(name: "alertLeak", value: dev_stat.is_leak, descriptionText: dev_stat.is_leak?statusMessages.getAt("is_leak"):"");
        tapLinker.sendEvent(name: "alertClogged", value: dev_stat.is_clog, descriptionText: dev_stat.is_clog?statusMessages.getAt("is_clog"):"");
        tapLinker.sendEvent(name: "manualMode", value: dev_stat.is_manual_mode);
        tapLinker.sendEvent(name: "planMode", value: waterModes.getAt(dev_stat.plan_mode));
        tapLinker.sendEvent(name: "childLock", value: childLock.getAt(dev_stat.child_lock));
    } else {
        logWarning("Device $deviceId does not exist, please add manually or reboot gateway for handshake protocol");
    }    
}


private ackHandshake(String gwId) {

 def date=new Date();
 logDebug("ENTERED ackHandshake WITH PARAMETERS $gwId");
 ackCommand('handshake ack', [gwId, date.format("yyyyMMdd"), date.format("HHmmss") , date.format("u") as Integer]);
 logDebug("END ackHandshake");
}


private ackSyncTime(String gwId) {
    def date=new Date();
    logDebug("ENTERED ackSyncTime WITH PARAMETERS $gwId");
    ackCommand('sync time ack', [gwId, date.format("yyyyMMdd"), date.format("HHmmss"), date.format("u") as Integer]);
    logDebug("END ackSyncTime");
}

/* 1.2 */
private rainSkipNotification(String deviceId) {
 
    logDebug("ENTERED rainSkipNotification FOR DEVICE $deviceId");

    def tapLinker=getChildDevice("$devicePrefix$deviceId");
    if (tapLinker) {
        tapLinker.sendEvent(name: "rainSkipNotification", value: (new Date()).format("yyyy-MM-dd HH:mm:ss"));
    }
}


private processDownAck(String deviceId, String stringMessage) {

    logDebug("ENTERED processDownAck FOR DEVICE $deviceId");
    
    def slurper = new groovy.json.JsonSlurper();
    def result=slurper.parseText(stringMessage);

    Integer cmd=result.cmd;
    logTrace("processDownAck: cmd: $cmd");

    String gatewayId=result.gw_id?:null;
    
    if (gatewayId) {logTrace("Gateway: ${gatewayId}");}
    if (deviceId) {logTrace("Device: ${deviceId}");}
    
    switch (cmd) {
        case 1: // confirm added device
            if (result.ret==0) {
                if (!state.deviceToAdd) logWarning("Received confirmation of device addition, but there is no device pending to be added by Hubitat");
                else if (gatewayId!=state.deviceToAdd[0]) logWarning("Received confirmation of device addition, but gatewayId does not match");
                else {
                    addOrUpdateDevices([state.deviceToAdd[1].substring(0,16)],state.deviceToAdd[0]);
                    logInfo("Created device ${state.deviceToAdd[1].substring(0,16)} on gateway ${state.deviceToAdd[0]}");
                    state.deviceToAdd=null;
                }
            }
            else {
                logWarning("Error received for ${downCommands.getAt(cmd)}: ${retCodes.getAt(result.ret?:0)}");
                state.deviceToAdd=null;
            }
        break;
        case 2: // confirm delete device
            if (result.ret==0) {
                if (!state.deviceToDelete) logWarning("Received confirmation of device deletion, but there is no device pending to be deleted by Hubitat");
                else if (gatewayId!=state.deviceToDelete[0]) logWarning("Received confirmation of device deletion, but gatewayId does not match");
                else {
                    deleteChildDevice("$devicePrefix${state.deviceToDelete[1]}");
                    logInfo("Deleted device ${state.deviceToDelete[1]}");
                    state.deviceToDelete=null;
                }
            }
            else {
                logWarning("Error received for ${downCommands.getAt(cmd)}: ${retCodes.getAt(result.ret?:0)}");
                state.deviceToDelete=null;
            }
        break;
        case 4: // ack for instruction commands
        case 5:
        case 6: 
        case 7:
        case 8:
        case 10:
        case 11:
        case 12:
            if (result.ret==0)
                logInfo("Success received for ${downCommands.getAt(cmd)}");
            else
                logWarning("Error received for ${downCommands.getAt(cmd)}: ${retCodes.getAt(result.ret?:0)}");
        break;
        case 14: // ack for fetch gateway time command
            if (result.ret==0)
                logInfo("Success received for ${downCommands.getAt(cmd)}: Date: ${result.date}, Time: ${result.time}, Weekday: ${result.wday}");
            else
                logWarning("Error received for ${downCommands.getAt(cmd)}: ${retCodes.getAt(result.ret?:0)}");
        break;
        case 15: // ack for wireless test command
            if (result.ret==0)
                processWirelessTestResult(deviceId, result.final, result.ping, result.pong);
            else
                logWarning("Error received for ${downCommands.getAt(cmd)}: ${retCodes.getAt(result.ret?:0)}");
        break;
        default:
            logWarning("Acknowledgement for unknown command received from gateway $cmd");
        break;
    }
    
    logDebug("END processDownAck");
}

private processWirelessTestResult(String deviceId, Boolean finalFlag, Integer ping, Integer pong) {
    logInfo("WIRELESS TEST for device $deviceId: Ping=$ping, Pong=$pong, Result=${(ping!=0)?((pong/ping)*100 as Integer):0}%.${finalFlag?' (FINAL)':''}");
}

def registerEndDevice(String gatewayId, String deviceId) {
    if (!state.deviceToAdd) {
        sendCommand('register device', [gatewayId, deviceId]);
        state.deviceToAdd=[gatewayId, deviceId];
        logTrace("deviceToAdd: ${state.deviceToAdd}");
    } else
        logWarning("Didn't process registration because we have another device pending creation");
}

def deleteEndDevice(String gatewayId, String deviceId) {
    if (!state.deviceToDelete) {
        sendCommand('delete device', [gatewayId, deviceId]);
        state.deviceToDelete=[gatewayId, deviceId];
        logTrace("deviceToDelete: ${state.deviceToDelete}");
    } else
        logWarning("Didn't process deletion because we have another device pending deletion");
}

/*1.2*/
private fetchRainfallData(String gatewayId) {
    BigDecimal a_obsRainData=device.currentValue("obsRainData");
    BigDecimal a_forRainData=device.currentValue("forRainData");
    Integer a_rainDuration=device.currentValue("rainDuration");
    long a_rainTimestamp=device.currentValue("rainUpdateTimestamp");
    long a_now=now();
    
    Integer b_rainDuration=a_rainDuration-(a_now-a_rainTimestamp)/60000;
    
    // check if attributes are set
    logDebug("ENTERED fetchRainfallData: $a_obsRainData, $a_forRainData, $b_rainDuration");
    Boolean rainDataIsSet=((a_obsRainData?:-1)>=0 && (a_forRainData?:-1)>=0 && (b_rainDuration?:0)>0);
    if (rainDataIsSet) {
        ackCommand('fetch rain data ack',[gatewayId, a_obsRainData, a_forRainData, b_rainDuration]);
    } else logWarning("Not all rain data is set");   
}

def clearPendingDevices() {
    state.deviceToAdd=null;
    state.deviceToDelete=null;
}

/*1.2*/
def updateRain(gatewayId, p_obsRainData, p_forRainData, p_rainDuration) {
    logDebug("ENTERED updateRain: $p_obsRainData, $p_forRainData, $p_rainDuration");
    sendEvent(name: "obsRainData", value: p_obsRainData);
    sendEvent(name: "forRainData", value: p_forRainData);
    sendEvent(name: "rainDuration", value: p_rainDuration);
    sendEvent(name: "rainUpdateTimestamp", value: now());
    sendCommand('update rain', [gatewayId, p_obsRainData, p_forRainData, p_rainDuration]);
    logDebug("END updateRain");
}

//**************** MQTT Message Processing

def publishMessage (String topic, String message) {
    logDebug("PUBLISH_MESSAGE: I am going to publish $message to $topic");
    interfaces.mqtt.publish(topic, message)
}

void sendCommand(method,args = []) {
    
    logDebug("ENTERED sendCommand FOR METHOD $method WITH ARGUMENTS ${args}")
    
    def methods = [
    'start water': ["cmd": 6, "gw_id": args[0], "dev_id": args[1], "duration": args[2]],
    'stop water': ["cmd": 7, "gw_id": args[0], "dev_id": args[1]],
    'fetch time': ["cmd": 14, "gw_id": args[0]],
    'dismiss alert': ["cmd": 11, "gw_id": args[0], "dev_id": args[1], "alert": alertTypes.getAt(args[2])],
    'set plan': ["cmd": 4, "gw_id": args[0], "dev_id": args[1], "plan_sn": args[2], "mode": args[3], "eco": [args[4],args[5]], "sch": ["timestamp": args[6], "duration": args[7]]],
    'delete plan': ["cmd": 5, "gw_id": args[0], "dev_id": args[1]],
    'child lock':  ["cmd": 12, "gw_id": args[0], "dev_id": args[1], "lock": childLock.find { it.value == args[2] }?.key],
    'enable alert': ["cmd": 10, "gw_id": args[0], "dev_id": args[1], "alert": alertTypes.getAt(args[2]), "enable": true],
    'disable alert': ["cmd": 10, "gw_id": args[0], "dev_id": args[1], "alert": alertTypes.getAt(args[2]), "enable": false],
    'fetch time': ["cmd": 14, "gw_id": args[0]],
    'test wireless': ["cmd": 15, "gw_id": args[0], "dev_id": args[1]],
    'register device': ["cmd": 1, "gw_id": args[0], "end_dev": [args[1]]],
    'delete device': ["cmd": 2, "gw_id": args[0], "end_dev": [args[1]]],
    'update rain': ["cmd": 8, "gw_id": args[0], "rain": [args[1],args[2]], "valid_duration": args[3]] //1.2
	]

	def request = methods.getAt(method)
    def json=new groovy.json.JsonBuilder(request).toString()

    publishMessage("/linktap/down_cmd",json);
    logDebug("END sendCommand");
}


void ackCommand(method,args = []) {
    
    logDebug("ENTERED ackCommand FOR METHOD $method WITH ARGUMENTS ${args}")
    
    def methods = [
        'handshake ack': ["cmd": 0, "gw_id": args[0], "date": args[1], "time": args[2], "wday": args[3]],
        'sync time ack': ["cmd": 13, "gw_id": args[0], "date": args[1], "time": args[2], "wday": args[3]],
/*1.2*/ 'fetch rain data ack': ["cmd": 8, "gw_id": args[0], "rain": [args[1],args[2]], "valid_duration": args[3]]
	]

	def request = methods.getAt(method)
    def json=new groovy.json.JsonBuilder(request).toString()
    publishMessage("/linktap/up_cmd_ack",json);
    logDebug("END ackCommand");
}
