package org.zstack.compute.vm;

import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.orm.jpa.JpaSystemException;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusListCallBack;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.workflow.Flow;
import org.zstack.core.workflow.FlowException;
import org.zstack.core.workflow.FlowTrigger;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.l3.*;
import org.zstack.header.vm.*;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.network.NetworkUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE)
public class VmAllocateNicFlow implements Flow {
    private static final CLogger logger = Utils.getLogger(VmAllocateNicFlow.class);
    @Autowired
    protected DatabaseFacade dbf;
    @Autowired
    protected CloudBus bus;
    @Autowired
    protected ErrorFacade errf;

    private VmNicVO persistAndRetryIfMacCollision(VmNicVO vo) {
        int tries = 5;
        while (tries-- > 0) {
            try {
                vo = dbf.persistAndRefresh(vo);
                return vo;
            } catch (JpaSystemException e) {
                if (e.getRootCause() instanceof MySQLIntegrityConstraintViolationException && e.getRootCause().getMessage().contains("Duplicate entry")) {
                    logger.debug(String
                            .format("Concurrent mac allocation. Mac[%s] has been allocated, try allocating another one. The error[Duplicate entry] printed by jdbc.spi.SqlExceptionHelper is no harm, we will try finding another mac",
                                    vo.getMac()));
                    logger.trace("", e);
                    vo.setMac(NetworkUtils.generateMacWithDeviceId((short) vo.getDeviceId()));
                } else {
                    throw e;
                }
            }
        }
        return null;
    }

    private void persistNicToDb(List<VmNicInventory> nics) {
        for (VmNicInventory nic : nics) {
            VmNicVO vo = new VmNicVO();
            vo.setUuid(nic.getUuid());
            vo.setIp(nic.getIp());
            vo.setL3NetworkUuid(nic.getL3NetworkUuid());
            vo.setUsedIpUuid(nic.getUsedIpUuid());
            vo.setVmInstanceUuid(nic.getVmInstanceUuid());
            vo.setDeviceId(nic.getDeviceId());
            vo.setMac(nic.getMac());
            vo.setNetmask(nic.getNetmask());
            vo.setGateway(nic.getGateway());
            vo.setInternalName(nic.getInternalName());
            vo = persistAndRetryIfMacCollision(vo);
            if (vo == null) {
                throw new FlowException(errf.instantiateErrorCode(VmErrors.ALLOCATE_MAC_ERROR, "unable to find an available mac address after re-try 5 times, too many collisions"));
            }
        }
    }

    @Override
    public void run(final FlowTrigger trigger, final Map data) {
        final VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
        List<AllocateIpMsg> msgs = new ArrayList<AllocateIpMsg>();
        for (final L3NetworkInventory nw : spec.getL3Networks()) {
            AllocateIpMsg msg = new AllocateIpMsg();

            List<Map<String, String>> tokenList = VmSystemTags.STATIC_IP.getTokensOfTagsByResourceUuid(spec.getVmInventory().getUuid());
            for (Map<String, String> tokens : tokenList) {
                String l3Uuid = tokens.get(VmSystemTags.STATIC_IP_L3_UUID_TOKEN);
                if (l3Uuid.equals(nw.getUuid())) {
                    msg.setRequiredIp(tokens.get(VmSystemTags.STATIC_IP_TOKEN));
                }
            }

            msg.setL3NetworkUuid(nw.getUuid());
            msg.setAllocateStrategy(spec.getIpAllocatorStrategy());
            bus.makeTargetServiceIdByResourceUuid(msg, L3NetworkConstant.SERVICE_ID, nw.getUuid());
            msgs.add(msg);
        }

        bus.send(msgs, new CloudBusListCallBack(trigger) {
            @Override
            public void run(List<MessageReply> replies) {
                ErrorCode err = null;
                for (MessageReply r : replies) {
                    if (r.isSuccess()) {
                        int deviceId = replies.indexOf(r);
                        AllocateIpReply areply = r.castReply();
                        VmNicInventory nic = new VmNicInventory();
                        nic.setUuid(Platform.getUuid());
                        nic.setIp(areply.getIpInventory().getIp());
                        nic.setUsedIpUuid(areply.getIpInventory().getUuid());
                        nic.setVmInstanceUuid(spec.getVmInventory().getUuid());
                        nic.setL3NetworkUuid(areply.getIpInventory().getL3NetworkUuid());
                        nic.setMac(NetworkUtils.generateMacWithDeviceId((short) deviceId));
                        nic.setDeviceId(deviceId);
                        nic.setNetmask(areply.getIpInventory().getNetmask());
                        nic.setGateway(areply.getIpInventory().getGateway());
                        nic.setInternalName(VmNicVO.generateNicInternalName(spec.getVmInventory().getInternalId(), nic.getDeviceId()));
                        spec.getDestNics().add(nic);
                    } else {
                        err = r.getError();
                    }
                }

                if (err != null) {
                    trigger.fail(err);
                } else {
                    persistNicToDb(spec.getDestNics());
                    trigger.next();
                }
            }
        });
    }

    @Override
    public void rollback(final FlowTrigger chain, Map data) {
        VmInstanceSpec spec = (VmInstanceSpec) data.get(VmInstanceConstant.Params.VmInstanceSpec.toString());
        final List<VmNicInventory> destNics = spec.getDestNics();
        if (destNics.isEmpty()) {
            chain.rollback();
            return;
        }

        List<ReturnIpMsg> msgs = new ArrayList<ReturnIpMsg>();
        final List<String> nicUuids = new ArrayList<String>();
        for (VmNicInventory nic : destNics) {
            ReturnIpMsg msg = new ReturnIpMsg();
            msg.setL3NetworkUuid(nic.getL3NetworkUuid());
            msg.setUsedIpUuid(nic.getUsedIpUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, L3NetworkConstant.SERVICE_ID, nic.getL3NetworkUuid());
            msgs.add(msg);

            nicUuids.add(nic.getUuid());
        }

        bus.send(msgs, 1, new CloudBusListCallBack(chain) {
            @Override
            public void run(List<MessageReply> replies) {
                dbf.removeByPrimaryKeys(nicUuids, VmNicVO.class);
                chain.rollback();
            }
        });
    }
}
