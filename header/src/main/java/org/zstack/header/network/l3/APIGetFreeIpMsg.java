package org.zstack.header.network.l3;

import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;

/**
 * Created by frank on 6/15/2015.
 */
public class APIGetFreeIpMsg extends APIMessage implements L3NetworkMessage {
    @APIParam(resourceType = L3NetworkVO.class, required = false)
    private String l3NetworkUuid;
    @APIParam(resourceType = IpRangeVO.class, required = false)
    private String ipRangeUuid;

    private int limit = 100;

    public String getL3NetworkUuid() {
        return l3NetworkUuid;
    }

    public void setL3NetworkUuid(String l3NetworkUuid) {
        this.l3NetworkUuid = l3NetworkUuid;
    }

    public String getIpRangeUuid() {
        return ipRangeUuid;
    }

    public void setIpRangeUuid(String ipRangeUuid) {
        this.ipRangeUuid = ipRangeUuid;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
