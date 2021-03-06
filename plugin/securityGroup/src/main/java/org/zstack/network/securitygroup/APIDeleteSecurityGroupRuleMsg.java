package org.zstack.network.securitygroup;

import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;

import java.util.List;
/**
 * @api
 * api event for :ref:`APIDeleteSecurityGroupRuleEvent`
 *
 * @category security group
 *
 * @since 0.1.0
 *
 * @cli
 *
 * @httpMsg
 * {
"org.zstack.network.securitygroup.APIDeleteSecurityGroupRuleMsg": {
"ruleUuids": [
"3f8e32673f2a429dbed7ea3e1041dd43"
],
"session": {
"uuid": "cdfd07ea63c043c998299decdf03ea58"
}
}
}
 *
 * @msg
 * {
"org.zstack.network.securitygroup.APIDeleteSecurityGroupRuleMsg": {
"ruleUuids": [
"3f8e32673f2a429dbed7ea3e1041dd43"
],
"session": {
"uuid": "cdfd07ea63c043c998299decdf03ea58"
},
"timeout": 1800000,
"id": "c320ad5f3c2f40879ad188ec65784f00",
"serviceId": "api.portal"
}
}
 *
 * @result
 * see :ref:`APIDeleteSecurityGroupRuleEvent`
 */
public class APIDeleteSecurityGroupRuleMsg extends APIMessage {
    /**
     * @desc a list of rule uuid
     */
    @APIParam(nonempty = true)
    private List<String> ruleUuids;

    public List<String> getRuleUuids() {
        return ruleUuids;
    }

    public void setRuleUuids(List<String> ruleUuids) {
        this.ruleUuids = ruleUuids;
    }
}
