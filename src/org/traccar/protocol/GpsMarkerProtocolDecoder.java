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
package org.traccar.protocol;

import org.jboss.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.Parser;
import org.traccar.helper.PatternBuilder;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class GpsMarkerProtocolDecoder extends BaseProtocolDecoder {

    public GpsMarkerProtocolDecoder(GpsMarkerProtocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("$GM")
            .number("d")                         // type
            .number("(?:xx)?")                   // index
            .number("(d{15})")                   // imei
            .number("T(dd)(dd)(dd)")             // date
            .number("(dd)(dd)(dd)?")             // time
            .expression("([NS])")
            .number("(dd)(dd)(dddd)")            // latitude
            .expression("([EW])")
            .number("(ddd)(dd)(dddd)")           // longitude
            .number("(ddd)")                     // speed
            .number("(ddd)")                     // course
            .number("(d)")                       // satellites
            .number("(dd)")                      // battery
            .number("(d)")                       // input
            .number("(d)")                       // output
            .number("(ddd)")                     // temperature
            .any()
            .compile();

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        Parser parser = new Parser(PATTERN, (String) msg);
        if (!parser.matches()) {
            return null;
        }

        Position position = new Position();
        position.setProtocol(getProtocolName());

        if (!identify(parser.next(), channel, remoteAddress)) {
            return null;
        }
        position.setDeviceId(getDeviceId());

        DateBuilder dateBuilder = new DateBuilder()
                .setDateReverse(parser.nextInt(), parser.nextInt(), parser.nextInt())
                .setTime(parser.nextInt(), parser.nextInt(), parser.nextInt());
        position.setTime(dateBuilder.getDate());

        position.setValid(true);
        position.setLatitude(parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG_MIN_MIN));
        position.setLongitude(parser.nextCoordinate(Parser.CoordinateFormat.HEM_DEG_MIN_MIN));
        position.setSpeed(parser.nextDouble());
        position.setCourse(parser.nextDouble());

        position.set(Event.KEY_SATELLITES, parser.next());
        position.set(Event.KEY_BATTERY, parser.next());
        position.set(Event.KEY_INPUT, parser.next());
        position.set(Event.KEY_OUTPUT, parser.next());
        position.set(Event.PREFIX_TEMP + 1, parser.next());

        return position;
    }

}
