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
import org.traccar.model.Position;

import com.fasterxml.jackson.core.JsonProcessingException;

public class MQTTDataHandler extends BaseDataHandler {

	private static int qos = 2;
	private static String topic = null;
	private static MqttClient client = null;
	//private static Map<String, Position> previousPositions = new HashMap<String, Position>();
	private static double minIdleSpeed = 0.5;
	private static double minSpeedDetectMovement = 1.0;
	private static double maxIdleTime = 300000;
	private static double minDistance = 0.00001;
	private static long numberOfReceived = 0;
	private static long numberOfSent = 0;
	
	private static Map<Long, Position> previousPositions = new HashMap<Long, Position>();
	
	public MQTTDataHandler() {
		initMQTTClient();
	}
	
	private Position getPreviousPosition(long deviceId) {
		return previousPositions.get(deviceId);
	}
	
	protected int getDistance(Position position) {
		
		if (position.getAttributes().get("io199") != null) {
			position.set(Position.KEY_DISTANCE, (Double.valueOf(position.getAttributes().get("io199").toString()).intValue()));
			return Double.valueOf(String.valueOf(position.getAttributes().get("io199"))).intValue();
		}
		if (position.getAttributes().get(Position.KEY_DISTANCE) != null) {
			return Double.valueOf(position.getAttributes().get(Position.KEY_DISTANCE).toString()).intValue();
		}
		
		position.set(Position.KEY_DISTANCE, 0);
		
		return Double.valueOf(String.valueOf(position.getAttributes().get(Position.KEY_DISTANCE))).intValue();
	}
	
//	protected void updateOdometer(Device device, Position position, Position previousPosition) {
//		device.setOdometer(device.getOdometer() + getDistance(position));
//		position.set("odometer", device.getOdometer());
//	}
	
	protected boolean powerChange(Device device, int key) {
		
		Position previousPosition = getPreviousPosition(device.getId());
		
		Integer previousKey = 2;
		
		if ((previousPosition != null) && (previousPosition.getAttributes().get("key") != null)) {
			previousKey = Double.valueOf(String.valueOf(previousPosition.getAttributes().get("key"))).intValue();
		} 

		return key != previousKey;
	}	
	
	protected Integer updatePosition(Position position, Device device) {	
		
		if (position.getAttributes().get("key") == null) {
			System.out.println("[ERROR] Key is null for deviceId="+device.getUniqueId());
			return null;
		}
		
		Position previousPosition = getPreviousPosition(device.getId());
		if ((previousPosition != null) && (position.getAttributes() != null)) {
			if ((position.getAttributes().get("odometer") == null) && (previousPosition.getAttributes().get("odometer") != null)) {
				System.out.println("[WARN] Odometer is not set, using the odometer from previous position for deviceId="+device.getUniqueId());
				position.set("odometer", Double.valueOf(String.valueOf(previousPosition.getAttributes().get("odometer"))));
			}
		}	
		
		int key = 1;
		try {
			key = Double.valueOf(position.getAttributes().get("key").toString()).intValue();
		} catch (Exception e) {
			System.out.println("[ERROR] "+e.getMessage()+" => key="+position.getAttributes().get("key")+" for deviceId="+ device.getUniqueId());
		}
		
		if (key == 1) {
			if (position.getSpeed() > minIdleSpeed) {
				if (powerChange(device, 1)) {
					start(position);
				} else {
					move(position, device);
				}
				return 1;
			} else /* idle */ {
				if (idleTooLong(position, device)) {
					if (powerChange(device, 0)) {
						stop(position);
					} else {
						rest(position, device);
					}
					return 0;
				} else {
					if (powerChange(device, 1)) {
						start(position);
					} else {
						move(position, device);
					}
					return 1; 
				}
			}
		} else /* key == 0 */ {
			if ((position.getSpeed() > minSpeedDetectMovement)) {
				if (powerChange(device, 1)) {
					start(position);
				} else {
					move(position, device);
				}
				return 1;
			} else {
				if (powerChange(device, 0)) {
					stop(position);
				} else {
					rest(position, device);
				}
				return 0;
			}
		}
	}

	private void stop(Position position) {
		position.set("key", 0);
		position.set("state", "stop");
		position.set("rest", UUID.randomUUID().toString());
		position.set("startRestTime", position.getDeviceTime().getTime());
		position.set("restTime", 0);
		position.set("maxSpeed", 0);
		position.setSpeed(0.0);
		
		position.getAttributes().remove("startTripTime");
		position.getAttributes().remove("tripTime");
		position.getAttributes().remove("startIdleTime");
		position.getAttributes().remove("idleTime");
	}

	private void start(Position position) {
		position.set("key", 1);
		position.set("state", "start");
		position.set("trip", UUID.randomUUID().toString());
		position.set("startTripTime", position.getDeviceTime().getTime());
		position.set("tripTime", 0);
		position.set("maxSpeed", position.getSpeed());
		position.getAttributes().remove("startIdleTime");
		position.getAttributes().remove("idleTime");
		position.getAttributes().remove("startRestTime");
		position.getAttributes().remove("restTime");
	}
	
	private void idle(Position position, Device device) {
		Position previousPosition = getPreviousPosition(device.getId());
		
		if ((previousPosition != null) && (previousPosition.getAttributes().get("startIdleTime") != null)) { 
		   long startIdleTime = (long)previousPosition.getAttributes().get("startIdleTime");
		   long idleTime = position.getDeviceTime().getTime() - startIdleTime;
		   position.set("startIdleTime", startIdleTime);
		   position.set("idleTime", idleTime);
		} else {
			position.set("startIdleTime", (long) position.getDeviceTime().getTime());
			position.set("idleTime", (long) 0);
		}	
	}
	
	private boolean idleTooLong(Position position, Device device) {
		idle(position, device);
		Position previousPosition = getPreviousPosition(device.getId());
		if ((previousPosition != null) && (previousPosition.getAttributes().get("idleTime") != null)) {
			return Double.valueOf(String.valueOf(position.getAttributes().get("idleTime"))).longValue() > maxIdleTime;
		}
		return false; 	
	}

	private void move(Position position, Device device) {
		double distance = 0;
		try {
			distance = Double.valueOf(getDistance(position));
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (distance < minDistance) {
			position.setSpeed(0);
		}
		position.set("key", 1);
		position.set("state", "start");
		Position previousPosition = getPreviousPosition(device.getId());
		if ((previousPosition != null) && (previousPosition.getAttributes().get("trip") != null)) {
			position.set("trip", String.valueOf(previousPosition.getAttributes().get("trip")));
			tripTime(position, device);
		} else {
			System.out.println("[ERROR] Trip should not be null here (move)! deviceId = " + device.getUniqueId()); 
		}
		
		double maxSpeed = previousPosition.getAttributes().get("maxSpeed") != null ? Double.valueOf(String.valueOf(previousPosition.getAttributes().get("maxSpeed"))) : 0;
		if (position.getSpeed() > maxSpeed) {
			position.set("maxSpeed", position.getSpeed());
		} else {
			position.set("maxSpeed", maxSpeed);
		}
		if (position.getSpeed() < minIdleSpeed) { //device idle	
			idle(position, device);
		}
	}

	private void tripTime(Position position, Device device) {
		Position previousPosition = getPreviousPosition(device.getId());
		if ((previousPosition != null) && (previousPosition.getAttributes().get("startTripTime") != null)) { 
		   long startIdleTime = (long)previousPosition.getAttributes().get("startTripTime");
		   long idleTime = position.getDeviceTime().getTime() - startIdleTime;
		   position.set("startTripTime", startIdleTime);
		   position.set("tripTime", idleTime);
		} else {
			position.set("startTripTime", (long) position.getDeviceTime().getTime());
			position.set("tripTime", (long) 0);
		}	
	}
	
	private void rest(Position position, Device device) {
		position.set("key", 0);
		position.set("state", "stop");
		position.setSpeed(0.0);
		Position previousPosition = getPreviousPosition(device.getId());
		if ((previousPosition != null) && (previousPosition.getAttributes().get("rest") != null)) {
			position.set("rest", String.valueOf(previousPosition.getAttributes().get("rest")));
		} else { 
			System.out.println("[ERROR] Rest should not be null here (rest)! deviceId = " + device.getUniqueId()); 
		}
		if (previousPosition.getAttributes().get("startRestTime") != null) {
			long startRestTime = (long)previousPosition.getAttributes().get("startRestTime");
			long restTime = position.getDeviceTime().getTime() - startRestTime;
			position.set("startRestTime", startRestTime);
			position.set("restTime", restTime);
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
//			System.out.print("Connecting to broker: " + url+".");
			client.connect(connOpts);
//		
			System.out.println("Connected.");
		} catch (MqttException e) {
			e.printStackTrace();
		}
		return client;
	}

	private String formatRequest(Position position) {
		
		Device device = Context.getIdentityManager().getById(position.getDeviceId());

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
//		.replace("##odometer##", String.valueOf(device.getOdometer()))
		.replace("##altitude##", String.valueOf(position.getAltitude()))
		.replace("##speed##", String.valueOf(position.getSpeed()))
		.replace("##maxSpeed##", String.valueOf(position.getAttributes().get("maxSpeed")))
		.replace("##course##", String.valueOf(position.getCourse()))
		.replace("##state##", String.valueOf(position.getAttributes().get("state")))
		.replace("##idle##", String.valueOf(position.getAttributes().get("idle")))
		.replace("##address##", position.getAddress() != null ?  position.getAddress().replace("\"",  "'") : "");
		
		try {
		 	if ((position.getAttributes() == null) || (position.getAttributes().isEmpty())) {
		 		request = request.replace("##attributes##", "{}");
		 	} else {
		 		request = request.replace("##attributes##", Context.getObjectMapper().writeValueAsString(position.getAttributes()));
		 	}
		} catch (JsonProcessingException e) {
			request = request.replace("##attributes##", "{}");
			e.printStackTrace();
		}
		return request;
	}
	
	private void initKey(Position position, Device device) {
		
		if (position.getAttributes().get("io239") != null) {
			position.set("key", Double.valueOf(position.getAttributes().get("io239").toString()).intValue());
			return;
		} 
		
		if (position.getAttributes().get("key") != null) {
			return;
		}
		
		Position previousPosition = getPreviousPosition(device.getId());
		
		if ((previousPosition != null) && (previousPosition.getAttributes().get("key") != null)) {
			position.set("key", Double.valueOf(previousPosition.getAttributes().get("key").toString()).intValue());		
		} else {
			position.set("key", 1);		
		}
	}
	
	@Override
	protected Position handlePosition(Position position) {
		Device device = Context.getIdentityManager().getById(position.getDeviceId());
//		updateOdometer(device, position, previousPositions.get(device.getId()));	
		if (device == null) {
			if (position != null) {
				System.out.println("[WARN] Unknown device: " + position.getDeviceId());
			} 
			return position;
		}
		synchronized (device) {
			if (numberOfReceived != numberOfSent) {
				System.out.println("[ERROR] Number of received messages != Number of sent messages: "+numberOfReceived+", "+numberOfSent);
			}
			numberOfReceived++;
//			System.out.println("[INFO] Received: " + position.toString()); 
			
			initKey(position, device);
				
			updatePosition(position, device);
			
			String content = formatRequest(position);
	
			MqttMessage message = new MqttMessage(content.getBytes());
			message.setQos(qos);
			try {
				client.publish(topic, message);
			} catch (MqttException e) {
				e.printStackTrace();
			}
			numberOfSent++;
//			System.out.println("[INFO] Send:" +  content.replaceAll("\\r\\n", ""));
		}
		
		previousPositions.put(device.getId(), position);
		return position;
		
	}

}