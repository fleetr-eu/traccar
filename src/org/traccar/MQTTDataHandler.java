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

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.glassfish.hk2.utilities.reflection.Logger;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.MiscFormatter;
import org.traccar.model.Position;

public class MQTTDataHandler extends BaseDataHandler {

	private static String url = Context.getConfig().getString("mqtt.url");
	private static String clientId = Context.getConfig().getString("mqtt.clientId");
	private static String user = Context.getConfig().getString("mqtt.user"); 
	private static String password =Context.getConfig().getString("mqtt.password");
	private static int qos = Context.getConfig().getInteger("mqtt.qos");
	private static String topic = Context.getConfig().getString("mqtt.topic");
	private static MqttClient client = null;
	
	static {
		
		try {
			client = new MqttClient(url, clientId, new MemoryPersistence());
			MqttConnectOptions connOpts = new MqttConnectOptions();
			connOpts.setUserName(user);
			connOpts.setPassword(password.toCharArray());
			connOpts.setCleanSession(true);
			System.out.print("Connecting to broker: " + url+".");
			client.connect(connOpts);
			System.out.println("Connected.");
		} catch (MqttException e) {
			e.printStackTrace();
		}
		
	}

	private String calculateStatus(Position position) {
		if (position.getAttributes().containsKey(Event.KEY_ALARM)) {
			return "0xF841"; // STATUS_PANIC_ON
		} else if (position.getSpeed() < 1.0) {
			return "0xF020"; // STATUS_LOCATION
		} else {
			return "0xF11C"; // STATUS_MOTION_MOVING
		}
	}

	public String formatRequest(Position position) {

		Device device = Context.getIdentityManager().getDeviceById(position.getDeviceId());

		String attributes = MiscFormatter.toJsonString(position.getAttributes());

		String template = Context.getConfig().getString("mqtt.template");

		String request = template
			.replace("##name##", device.getName())
			.replace("##uniqueId##", device.getUniqueId())
			.replace("##deviceId##", String.valueOf(position.getDeviceId()))
			.replace("##protocol##", String.valueOf(position.getProtocol()))
			.replace("##deviceTime##", String.valueOf(position.getDeviceTime().getTime()))
			.replace("##fixTime##", String.valueOf(position.getFixTime().getTime()))
			.replace("##valid##", String.valueOf(position.getValid()))
			.replace("##latitude##", String.valueOf(position.getLatitude()))
			.replace("##longitude##", String.valueOf(position.getLongitude()))
			.replace("##altitude##", String.valueOf(position.getAltitude()))
			.replace("##speed##", String.valueOf(position.getSpeed()))
			.replace("##course##", String.valueOf(position.getCourse()))
			.replace("##statusCode##", calculateStatus(position));

		if (position.getAddress() != null) {
			request = request.replace("##address##", position.getAddress());
		}

		if (request.contains("##attributes##")) {
			request = request.replace("##attributes##", attributes);
		}

		return request;
	}

	@Override
	protected Position handlePosition(Position position) {

		String content = formatRequest(position);

		System.out.println("Publishing to topic="+topic+ ": "+content);
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
