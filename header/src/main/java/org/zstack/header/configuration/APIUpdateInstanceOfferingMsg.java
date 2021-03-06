package org.zstack.header.configuration;

import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;

/**
 * Created by frank on 6/15/2015.
 */
public class APIUpdateInstanceOfferingMsg extends APIMessage implements InstanceOfferingMessage {
    @APIParam(resourceType = InstanceOfferingVO.class)
    private String uuid;
    @APIParam
    private String name;
    @APIParam
    private String description;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String getInstanceOfferingUuid() {
        return uuid;
    }
}
