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
import org.traccar.model.Event;
import org.traccar.model.MiscFormatter;
import org.traccar.model.Position;

public class MQTTDataHandler extends BaseDataHandler {


	private static int qos = 2;
	private static String topic = null;
	private static MqttClient client = null;
	private static Map<String, Integer> power = new HashMap<String, Integer>();
	private static Map<String, String> trips = new HashMap<String, String>();
	private static Map<String, String> rests = new HashMap<String, String>();
	private static Map<String, String> idles = new HashMap<String, String>();
	private static double minIdleSpeed = 1.0;

	public MQTTDataHandler() {
		initMQTTClient();
	}

	private String formatRequest(Position position) {

		Device device = Context.getIdentityManager().getDeviceById(position.getDeviceId());

		short state = updateState(position, device);

		String template = Context.getConfig().getString("mqtt.template");

		String request = template
			.replace("##name##", device.getName())
			.replace("##uniqueId##", device.getUniqueId())
			.replace("##deviceId##", String.valueOf(position.getDeviceId()))
			.replace("##protocol##", String.valueOf(position.getProtocol()))
			.replace("##eventType##", String.valueOf(position.getAttributes().get("eventType")))
			.replace("##deviceTime##", String.valueOf(position.getDeviceTime().getTime()))
			.replace("##fixTime##", String.valueOf(position.getFixTime().getTime()))
			.replace("##valid##", String.valueOf(position.getValid()))
			.replace("##latitude##", String.valueOf(position.getLatitude()))
			.replace("##longitude##", String.valueOf(position.getLongitude()))
			.replace("##altitude##", String.valueOf(position.getAltitude()))
			.replace("##speed##", String.valueOf(position.getSpeed()))
			.replace("##course##", String.valueOf(position.getCourse()))
			.replace("##state##", String.valueOf(state))
			.replace("##io##", String.valueOf(position.getAttributes().get("io")))
			.replace("##idle##", String.valueOf(position.getAttributes().get("idle")))
			.replace("##address##", position.getAddress() != null ?  position.getAddress() : "")
		 	.replace("##attributes##", MiscFormatter.toJsonString(position.getAttributes()));

		return request;
	}

	private short updateState(Position position, Device device) {

		Integer newPowerState = (Integer) position.getAttributes().get("io239");
		Integer previousPowerState = power.get(device.getUniqueId());

		String trip = trips.get(device.getUniqueId()) == null ? UUID.randomUUID().toString() : trips.get(device.getUniqueId());
		String rest = rests.get(device.getUniqueId()) == null ? UUID.randomUUID().toString() : rests.get(device.getUniqueId());

		short state = 1;
		short io = 255;
		int eventType = 30;
		if (previousPowerState != newPowerState) {
			power.put(device.getUniqueId(), newPowerState);
			eventType = 29;

			if (newPowerState == 1) { // new trip
				trip = UUID.randomUUID().toString();
				state = 1;
				io = 255;
			} else { // new rest
				rest = UUID.randomUUID().toString();
				state = 0;
				io = 254;
			}
		}
		position.set("eventType", eventType);
		position.set("io", io);

		trips.put(device.getUniqueId(), trip);
		rests.put(device.getUniqueId(), rest);

		position.set("trip", trip);
		position.set("rest", rest);

		if (position.getSpeed() < minIdleSpeed) {
			String idle = idles.get(device.getUniqueId());
			if (idle == null) { // new idle
				idle = UUID.randomUUID().toString();
				idles.put(device.getUniqueId(), idle);
			}
			position.set("idle", idle);
			if (state == 1) {
				position.set("idle", true);
			}
		} else {
			idles.remove(device.getUniqueId());
		}

		return state;
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
