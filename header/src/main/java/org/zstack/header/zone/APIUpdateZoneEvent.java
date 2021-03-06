package org.zstack.header.zone;

import org.zstack.header.message.APIEvent;

/**
 * Created by frank on 6/14/2015.
 */
public class APIUpdateZoneEvent extends APIEvent {
    private ZoneInventory inventory;

    public APIUpdateZoneEvent() {
    }

    public APIUpdateZoneEvent(String apiId) {
        super(apiId);
    }

    public ZoneInventory getInventory() {
        return inventory;
    }

    public void setInventory(ZoneInventory inventory) {
        this.inventory = inventory;
    }
}
