# Hubitat-LinktapMQTT
Hubitat driver for smart watering devices from Linktap with local network integration through the use of a MQTT broker

**Introduction**

This driver is for smart watering devices from Linktap (https://www.link-tap.com) with local network integration through the use of a MQTT broker.
Linktap solution officially supports integration through API exposed by their cloud server, which limits the possibility to integrate with Hubitat as it requires to open a local network port to listen to status updates from Linktap’s cloud server. I have created a Hubitat driver for this integration method - https://github.com/pedroandrade1977/Hubitat-Linktap.

Linktap is currently working on a version of the firmware which allows local network integration using MQTT protocol. With this approach, Hubitat can issue commands and receive updates from Linktap devices without communicating outside the local network.


**Pre-Requisites**

1.	One or more Linktap gateways setup in the local network. Gateways need to be setup using the Linktap App
2.	A running MQTT broker in the local network. The drivers were tested using Mosquitto MQTT broker (https://mosquitto.org)
3.	The beta version of the Linktap firmware with MQTT support installed on your gateway(s). You can contact Linktap for this, they were very helpful and supportive, and you should not have an issue to get them to push this firmware to your device


**Supported Features**
The initial version of the Hubitat drivers support the following capabilities:
1.	Recognize and create automatically in Hubitat all watering devices already setup and connected to a gateway
2.	Register a new watering device to an existing gateway
3.	Delete a watering device from one of the gateways
4.	Receive and store watering device status:
    a.	Connection status
    b.	Connection signal strength
    c.	Battery status
    d.	Flow meter connection status (for G2s)
    e.	Valve status: open/closed
    f.	Currently setup type of watering plan (e.g. instant)
    g.	Current watering session details (duration, remaining, flow rate, water volume)
    h.	Physical button lock (child lock)
    i.	Alerts
5.	Start watering
6.	Start watering with parametric duration (in seconds)
7.	Stop watering
8.	Setup a scheduled watering (for a specific start date/time and duration)
9.	Delete a scheduled watering plan
10.	Enable/disable alerts from the device
11.	Clear/dismiss previously issued alerts from the device
12.	Retrieve current gateway date/time
13.	Device wireless test
14.	Send local rain data to gateway for rain skipping functionality

**MQTT Connection Management**
- Allows scheduling a connectivity test every x seconds
- If connectivity has been lost, the driver will automatically try to reconnect
- You can specify the number of reconnection attempts. When this is reached, the driver will give up and reconnection should be performed manually

**Watering volumes calculation**
- 4 new attributes to display daily, weekly monthly and yearly water volumes
- Attributes are reset (respectively) at midnight (daily), sunday (weekly), 1st of the month (monthly) and 1st january (yearly) through a scheduled execution of a handler method

**Not supported features**

Linktap provides the option to have both cloud (App) and local (MQTT) integrations active. While several features work, especially related to device status and watering start/stop, simultaneous control through the two methods can create behavior inconsistencies, especially in what refers to registration/deletion of end devices. **If you intend to keep the cloud integration active when setting up local integration, I strongly recommend that you setup all devices through the app only, and not through Hubitat.**

The following feature are supported by the Linktap gateway firmware, but not implemented in the Hubitat driver:
- Other types of watering plans (e.g. Calendar, 7-day, Odd-even, Interval, Month). The input parameters for setting up these types of plans are complex and not suited to Hubitat UI, Rule Machine, etc.. If someone has a specific need, happy to evaluate the possibility of implementing some of these in the future.

The following features are not currently supported by the Linktap gateway firmware through MQTT integration:
1.	Eco mode
2.	Rain control
3.	High flow alert
4.	Low flow alert
5.	Device model

The following features while supported have not been thoroughly tested, as I only own one gateway and device:
  •	Multiple gateways
  •	Multiple devices per gateway
  •	Alerts besides the water cut-off


**Installation**

1.	Copy the code for the two drivers into Hubitat:
a.	Linktap MQTT Controller
b.	Linktap MQTT Taplinker
2.	Create a new Virtual Device of type Linktap MQTT Controller
3.	Enter the correct parameters for the configuration of the controller
    a.	Broker IP: IP of the MQTT Broker
    b.	Broker Port: Port of the MQTT Broker
    c.	Username: Username for the client to connect to the MQTT Broker
    d.	Password: Password for the client to connect to the MQTT Broker
      i.	Note: I have not tested anonymous connection to MQTT broker, if required let me know to try to support it
    e.	Log Level:
      i.	0: Error only
      ii.	1: Also Warnings
      iii.	2: Also Information
      iv.	3: Also Debug
      v.	4: Also Trace
    f.	Press Save Preferences
4.	Connect to MQTT Broker by pressing the MQTTConnect button. Status and errors (if any) will be printed to log
5.	Configure each Linktap gateway (very important):
    a.	Open the gateway configuration page, by entering the IP of the gateway in your local network in a browser
    b.	Update the options as per the screenshot, pressing Submit after filling in each table
    c.	Reboot the gateway (button at the bottom of page)
6.	After rebooting each gateway, it should send a handshake message to the broker, which Hubitat will use to create all devices managed. These will appear as child devices of the Controller device
    a.	You can change the name of the device if needed, it is not used for identification purpose (e.g. you can use Backyard, Patio, etc.)
7.	If you add later new devices through the App, you should also reboot the gateway to ensure the devices will be created in Hubitat also


**Usage**

In each child device, the supported commands and associated parameters are available. These should be self-explanatory, but if not feel free to drop me message  for any questions.

Note:
  •	Gateway ID always shows the first 16 digits
  •	Device ID show the first 16 digits, except when registering a new device, where the full 20 digits should be used (without dashes)
