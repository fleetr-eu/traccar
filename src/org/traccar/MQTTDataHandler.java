/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar;

import java.util.UUID;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.traccar.model.Device;
import org.traccar.model.MiscFormatter;
import org.traccar.model.Position;

public class MQTTDataHandler extends OdometerHandler {

	private static int qos = 2;
	private static String topic = null;
	private static MqttClient client = null;
	//private static Map<String, Position> previousPositions = new HashMap<String, Position>();
	private static double minIdleSpeed = 1.0;
	private static double minSpeedDetectMovement = 5.0;
	private static double maxIdleTime = 90000;
	
	public MQTTDataHandler() {
		initMQTTClient();
	}
	
	protected Integer getPreviousPowerState() {
		if (previousPosition == null) {
			return null;
		} 
		return Double.valueOf(String.valueOf(previousPosition.getAttributes().get("power"))).intValue();
	}	
	
	protected Integer getPowerState() {
		if (position.getAttributes().get("io239") != null) {
			return Double.valueOf(position.getAttributes().get("io239").toString()).intValue();
		} 
		
		if (position.getAttributes().get("key") != null) {
			int key = Double.valueOf(position.getAttributes().get("key").toString()).intValue();
		
			if (key == 0) {
				if ((position.getSpeed()  > minSpeedDetectMovement)) {
					return 1;
				} else {	
					return 0;
				}			
			} else { 
				if (position.getSpeed() > minIdleSpeed) {
					return 1;
				} else {
					if (position.getAttributes().remove("idleTime") != null) {	
						if (Double.valueOf(position.getAttributes().get("key").toString()).longValue() > maxIdleTime) {
							position.getAttributes().remove("startIdleTime");
							position.getAttributes().remove("idleTime");
							return 0;
						} else {
							return 1;
						}
					} else { 
						return 1;
					}
				}
			}
		} else {
			System.out.println("[ERROR] Something went wrong: key:null for deviceId"+device.getUniqueId());
			return 0;
		}
	}	
	
	private void updatePosition() {
			
		Integer newPowerState = getPowerState();
		Integer previousPowerState = getPreviousPowerState();
		
		if (newPowerState.equals(previousPowerState)) {
			if (newPowerState.equals(1)) { 
				move();
			} else {
				rest();
			}
		} else {
			if (newPowerState.equals(1)) { 
				start(); 
			} else {
				stop();
			}
		}
		
		position.set("power", newPowerState);	
		
//		String status = newPowerState == 1 ? Device.STATUS_ONLINE : Device.STATUS_OFFLINE;
//		Context.getConnectionManager().updateDevice(device.getId(), status, position.getDeviceTime());
	}

	private void stop() {
		position.set("state", "stop");
		position.set("rest", UUID.randomUUID().toString());
		position.set("startRestTime", position.getDeviceTime().getTime());
		position.set("restTime", 0);
		position.set("maxSpeed", 0);
		updateOdometer(device, position);
	}

	private void start() {
		position.set("state", "start");
		position.set("trip", UUID.randomUUID().toString());
		position.set("startTripTime", position.getDeviceTime().getTime());
		position.set("tripTime", 0);
		position.set("maxSpeed", position.getSpeed());
		updateOdometer(device, position);
	}

	private void move() {
		position.set("state", "start");
	
		if (previousPosition.getAttributes().get("trip") != null) {
			updateOdometer(device, position);
			position.set("trip", String.valueOf(previousPosition.getAttributes().get("trip")));
			tripTime();
		} else {
			start();
		}
		
		double maxSpeed = previousPosition.getAttributes().get("maxSpeed") != null ? Double.valueOf(String.valueOf(previousPosition.getAttributes().get("maxSpeed"))) : 0;
		if (position.getSpeed() > maxSpeed) {
			position.set("maxSpeed", position.getSpeed());
		} else {
			position.set("maxSpeed", maxSpeed);
		}
		if (position.getSpeed() < minIdleSpeed) { //device idle	
			idle();
		}
	}

	private void idle() {
		if (previousPosition.getAttributes().get("startIdleTime") != null) { 
		   long startIdleTime = (long)previousPosition.getAttributes().get("startIdleTime");
		   long idleTime = position.getDeviceTime().getTime() - startIdleTime;
		   position.set("startIdleTime", startIdleTime);
		   position.set("idleTime", idleTime);
		} else {
			position.set("startIdleTime", (long) position.getDeviceTime().getTime());
			position.set("idleTime", (long) 0);
		}	
	}
	
	private void tripTime() {
		if (previousPosition.getAttributes().get("startTripTime") != null) { 
		   long startIdleTime = (long)previousPosition.getAttributes().get("startTripTime");
		   long idleTime = position.getDeviceTime().getTime() - startIdleTime;
		   position.set("startTripTime", startIdleTime);
		   position.set("tripTime", idleTime);
		} else {
			position.set("startTripTime", (long) position.getDeviceTime().getTime());
			position.set("tripTime", (long) 0);
		}	
	}
	
	private void rest() {
		position.set("state", "stop");
		if (previousPosition.getAttributes().get("rest") != null) {
			position.set("rest", String.valueOf(previousPosition.getAttributes().get("rest")));
		}
		if (previousPosition.getAttributes().get("startRestTime") != null) {
			long startRestTime = (long)previousPosition.getAttributes().get("startRestTime");
			long restTime = position.getDeviceTime().getTime() - startRestTime;
			position.set("startRestTime", startRestTime);
			position.set("restTime", restTime);
		} else {
			rest();
		} 
	}

	protected static MqttClient initMQTTClient() {
		if (client != null) {
			return client;
		}
		String url = Context.getConfig().getString("mqtt.url");
		String clientId = Context.getConfig().getString("mqtt.clientId");
		String user = Context.getConfig().getString("mqtt.user");
		String password = Context.getConfig().getString("mqtt.password");
		topic = Context.getConfig().getString("mqtt.topic");
		qos = Context.getConfig().getInteger("mqtt.qos");
		minIdleSpeed = Double.parseDouble(Context.getConfig().getString("fleetr.minIdleSpeed"));
		try {
			client = new MqttClient(url, clientId, new MemoryPersistence());
			MqttConnectOptions connOpts = new MqttConnectOptions();
			if (user != null) connOpts.setUserName(user);
			if (password != null) connOpts.setPassword(password.toCharArray());
			connOpts.setCleanSession(true);
			System.out.print("Connecting to broker: " + url+".");
			client.connect(connOpts);
			System.out.println("Connected.");
		} catch (MqttException e) {
			e.printStackTrace();
		}
		return client;
	}

	private String formatRequest(Position position) {

		Device device = Context.getIdentityManager().getDeviceById(position.getDeviceId());

		String template = Context.getConfig().getString("mqtt.template");

		String request = template
			.replace("##name##", device.getName())
			.replace("##deviceId##", device.getUniqueId())
			.replace("##protocol##", String.valueOf(position.getProtocol()))
			.replace("##deviceTime##", String.valueOf(position.getDeviceTime().getTime()))
			.replace("##fixTime##", String.valueOf(position.getFixTime().getTime()))
			.replace("##valid##", String.valueOf(position.getValid()))
			.replace("##latitude##", String.valueOf(position.getLatitude()))
			.replace("##longitude##", String.valueOf(position.getLongitude()))
			.replace("##distance##", String.valueOf(getDistance(position)))
			.replace("##odometer##", String.valueOf(device.getOdometer()))
			.replace("##altitude##", String.valueOf(position.getAltitude()))
			.replace("##speed##", String.valueOf(position.getSpeed()))
			.replace("##maxSpeed##", String.valueOf(position.getAttributes().get("maxSpeed")))
			.replace("##course##", String.valueOf(position.getCourse()))
			.replace("##state##", String.valueOf(position.getAttributes().get("state")))
			.replace("##idle##", String.valueOf(position.getAttributes().get("idle")))
			.replace("##address##", position.getAddress() != null ?  position.getAddress() : "")
		 	.replace("##attributes##", MiscFormatter.toJsonString(position.getAttributes()));
		return request;
	}
	
	@Override
	protected Position handlePosition(Position position) {

		
		System.out.println("[INFO] Received: " + position.toString()); 
		
		super.handlePosition(position);
		
		updatePosition();
		
		String content = formatRequest(position);

		MqttMessage message = new MqttMessage(content.getBytes());
		message.setQos(qos);
		try {
			client.publish(topic, message);
		} catch (MqttException e) {
			e.printStackTrace();
		}
		
		System.out.println("[INFO] Send:" +  content+"\n");
		
		return position;
	}

}
