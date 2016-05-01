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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.traccar.model.Device;
import org.traccar.model.MiscFormatter;
import org.traccar.model.Position;

public class MQTTDataHandler extends BaseDataHandler {

	private static int qos = 2;
	private static String topic = null;
	private static MqttClient client = null;
	private static Map<String, Position> previousPositions = new HashMap<String, Position>();
	private static double minIdleSpeed = 1.0;
	private static double maxIdleTime = 90000;
	

	public MQTTDataHandler() {
		initMQTTClient();
	}

	private String formatRequest(Position position) {

		Device device = Context.getIdentityManager().getDeviceById(position.getDeviceId());

		updatePositionAttributes(position, device);
		if (device.getPhone() && (position.getAttributes().get("idleTime") != null)) {
			System.out.println("Idle="+(Long)position.getAttributes().get("idleTime"));
			if ((Long)position.getAttributes().get("idleTime") > maxIdleTime) {
				position.set("key", "0");
				position.getAttributes().remove("trip");
				position.getAttributes().remove("startIdleTime");
				position.getAttributes().remove("idleTime");
				updatePositionAttributes(position, device);
			}
		}

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
			.replace("##distance##", position.getAttributes().get("io199") != null ? String.valueOf(position.getAttributes().get("io199")) : String.valueOf(position.getAttributes().get("distance")))
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
	
	protected Integer getPowerState(Position position, Position previousPosition) {
		if (position.getAttributes().get("io239") != null) {
			return Integer.valueOf((String)position.getAttributes().get("io239"));
		} else {
			if (position.getAttributes().get("key") != null) {
				return Integer.valueOf((String)position.getAttributes().get("key"));
			} else {
				if (previousPosition.getAttributes().get("key") != null) {
					return Integer.valueOf((String)previousPosition.getAttributes().get("key"));
				} else {
					if (position.getSpeed() > minIdleSpeed) {
						return 1;
					}
				}
			}
		} 
		return 0;
	}
	
	private void updatePositionAttributes(Position position, Device device) {
	
		Position previousPosition = previousPositions.get(device.getUniqueId());
		
		if (previousPosition == null) {
			previousPosition = new Position();
		}
			
		Integer newPowerState = getPowerState(position, previousPosition);
		
		if ((Integer)previousPosition.getAttributes().get("power") != newPowerState) { //key on/off state has changed
			
			if (newPowerState == 1) { // new trip
				position.set("trip", UUID.randomUUID().toString());
				position.set("maxSpeed", position.getSpeed());
				position.set("state", "start");
				updateIdle(position, previousPosition);
			} else { //new rest
				position.set("rest", UUID.randomUUID().toString());
				position.set("maxSpeed", 0);
				position.set("startRestTime", position.getDeviceTime().getTime());
				position.set("restTime", 0);
				position.set("state", "stop");
			}
		} else {
			position.set("state", (String) previousPosition.getAttributes().get("state"));
			
			if (previousPosition.getAttributes().get("trip") != null) {
				position.set("trip", (String) previousPosition.getAttributes().get("trip"));
			}
			if (previousPosition.getAttributes().get("rest") != null) {
				position.set("rest", (String) previousPosition.getAttributes().get("rest"));
			}
			
			if (newPowerState == 1) { //device moving
				if (position.getSpeed() > previousPosition.getSpeed()) {
					position.set("maxSpeed", position.getSpeed());
				} else {
					position.set("maxSpeed", (Double) previousPosition.getAttributes().get("maxSpeed"));
				}
				updateIdle(position, previousPosition);
				
			} else { // device resting
				if (previousPosition.getAttributes().get("startRestTime") != null) {
					long startRestTime = (Long)previousPosition.getAttributes().get("startRestTime");
					long restTime = position.getDeviceTime().getTime() - startRestTime;
					position.set("startRestTime", startRestTime);
					position.set("restTime", restTime);
				} else {
					position.set("startRestTime", (long)position.getDeviceTime().getTime());
					position.set("restTime", (long) 0);
				}
			}
		}
		
		position.set("power", newPowerState);
		device.setStatus(newPowerState == 1 ? Device.STATUS_ONLINE : Device.STATUS_OFFLINE);
		previousPositions.put(device.getUniqueId(), position);
	}

	private void updateIdle(Position position, Position previousPosition) {
		if (position.getSpeed() < minIdleSpeed) { //device idle	
			if (previousPosition.getAttributes().get("startIdleTime") != null) { 
			   long startIdleTime = (Long)previousPosition.getAttributes().get("startIdleTime");
			   long idleTime = position.getDeviceTime().getTime() - startIdleTime;
			   position.set("startIdleTime", startIdleTime);
			   position.set("idleTime", idleTime);
			} else {
				position.set("startIdleTime", (long) position.getDeviceTime().getTime());
				position.set("idleTime", (long) 0);
			}	
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

	@Override
	protected Position handlePosition(Position position) {

		String content = formatRequest(position);

		System.out.println("Publishing to topic="+topic+": "+content);

		MqttMessage message = new MqttMessage(content.getBytes());
		message.setQos(qos);
		try {
			client.publish(topic, message);
		} catch (MqttException e) {
			e.printStackTrace();
		}
		return position;
	}

}
