package org.zstack.header.network.l3;

import org.zstack.header.message.APIDeleteMessage;
import org.zstack.header.message.APIParam;
import org.zstack.header.rest.APINoSee;
/**
 * @api
 * delete ip range
 *
 * @category l3Network
 *
 * @since 0.1.0
 *
 * @cli
 *
 * @httpMsg
 * {
"org.zstack.header.network.l3.APIDeleteIpRangeMsg": {
"uuid": "9dc2c43298a94b25bbd6a192d3913c38",
"deleteMode": "Permissive",
"session": {
"uuid": "fc1c1d1030e644e3a5e9e6b95bf922d0"
}
}
}
 *
 * @msg
 * {
"org.zstack.header.network.l3.APIDeleteIpRangeMsg": {
"uuid": "9dc2c43298a94b25bbd6a192d3913c38",
"deleteMode": "Permissive",
"session": {
"uuid": "fc1c1d1030e644e3a5e9e6b95bf922d0"
},
"timeout": 1800000,
"id": "7eced738ea56424280da80590db55663",
"serviceId": "api.portal"
}
}
 *
 * @result
 * see :ref:`APIDeleteIpRangeEvent`
 */
public class APIDeleteIpRangeMsg extends APIDeleteMessage implements L3NetworkMessage, IpRangeMessage {
    /**
     * @desc ip range uuid
     */
    @APIParam
    private String uuid;
    /**
     * @ignore
     */
    @APINoSee
    private String l3NetworkUuid;

    public APIDeleteIpRangeMsg() {
    }
    
    public APIDeleteIpRangeMsg(String uuid) {
        super();
        this.uuid = uuid;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public void setL3NetworkUuid(String l3NetworkUuid) {
        this.l3NetworkUuid = l3NetworkUuid;
    }

    @Override
    public String getL3NetworkUuid() {
        return l3NetworkUuid;
    }

    @Override
    public String getIpRangeUuid() {
        return uuid;
    }
}
