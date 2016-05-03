package org.traccar;

import java.sql.SQLException;

import org.traccar.database.DataManager;
import org.traccar.database.QueryBuilder;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;

public class OdometerHandler extends BaseDataHandler {
	
	protected Device device = null;
	protected Position position = null;
	protected Position previousPosition = null;
	
	private String getQuery(String key) {
        String query = Context.getConfig().getString(key);
        if (query == null) {
            Log.info("Query not provided: " + key);
        }
        return query;
    }
	
	private void initPreviousPosition() {
		if (previousPosition != null) {
			return;
		}
		
		if (device == null) {
			previousPosition = null;
			return;
		}			
		
		DataManager dataManager = Context.getDataManager();
		
		try {
			previousPosition = QueryBuilder.create(dataManager.getDataSource(), 
					getQuery("database.selectLatestPosition"))
			        .setLong("positionId", device.getPositionId())
			        .executeQuerySingle(Position.class);
		} catch (SQLException e) {
			e.printStackTrace();
			previousPosition = null;
		}
		if ((previousPosition != null) && (position.getAttributes().get("key") == null)) {
			position.set("key", Double.valueOf(position.getAttributes().get("key").toString()).intValue());
		}
	}
	
	protected int getDistance(Position position) {
		if (position.getAttributes().get("io199") != null) {
			position.set(Event.KEY_DISTANCE, (Double.valueOf(position.getAttributes().get("io199").toString()).intValue()));
			return Double.valueOf(String.valueOf(position.getAttributes().get("io199"))).intValue();
		}
		if (position.getAttributes().get(Event.KEY_DISTANCE) != null) {
			return Double.valueOf(position.getAttributes().get(Event.KEY_DISTANCE).toString()).intValue();
		}
		
		if (previousPosition != null) {
			double distance = DistanceCalculator.distance(
                position.getLatitude(), position.getLongitude(),
                previousPosition.getLatitude(), previousPosition.getLongitude());
			position.set(Event.KEY_DISTANCE, distance);
		} else {  
			position.set(Event.KEY_DISTANCE, 0);
		}
		return Double.valueOf(String.valueOf(position.getAttributes().get(Event.KEY_DISTANCE))).intValue();
	}
	
	protected void updateOdometer(Device device, Position position) {
		device.setOdometer(device.getOdometer() + getDistance(position));
	}
	
	@Override
	protected Position handlePosition(Position position) {
		
		device = Context.getIdentityManager().getDeviceById(position.getDeviceId());
		this.position = position;
		initPreviousPosition();
		
		device = Context.getIdentityManager().getDeviceById(position.getDeviceId());
		if (device == null) {
			return position;
		}
		
		updateOdometer(device, position);
		
		return position;
	}
}
