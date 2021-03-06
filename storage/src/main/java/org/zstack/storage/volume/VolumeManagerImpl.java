package org.zstack.storage.volume;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.db.DbEntityLister;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.workflow.*;
import org.zstack.header.AbstractService;
import org.zstack.header.configuration.DiskOfferingVO;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.image.ImageBackupStorageRefInventory;
import org.zstack.header.image.ImageBackupStorageRefVO;
import org.zstack.header.image.ImageInventory;
import org.zstack.header.image.ImageVO;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.search.SearchOp;
import org.zstack.header.storage.backup.*;
import org.zstack.header.storage.primary.*;
import org.zstack.header.storage.snapshot.*;
import org.zstack.header.volume.*;
import org.zstack.header.volume.APIGetVolumeFormatReply.VolumeFormatReplyStruct;
import org.zstack.identity.AccountManager;
import org.zstack.search.SearchQuery;
import org.zstack.tag.TagManager;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VolumeManagerImpl extends AbstractService implements VolumeManager {
	private static final CLogger logger = Utils.getLogger(VolumeManagerImpl.class);

	@Autowired
	private CloudBus bus;
	@Autowired
	private DatabaseFacade dbf;
	@Autowired
	private DbEntityLister dl;
	@Autowired
	private AccountManager acntMgr;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private TagManager tagMgr;

    private void passThrough(VolumeMessage vmsg) {
        Message msg = (Message)vmsg;
        VolumeVO vo = dbf.findByUuid(vmsg.getVolumeUuid(), VolumeVO.class);
        if (vo == null) {
            bus.replyErrorByMessageType(msg, String.format("Cannot find volume[uuid:%s], it may have been deleted", vmsg.getVolumeUuid()));
            return;
        }

        VolumeBase volume = new VolumeBase(vo);
        volume.handleMessage(msg);
    }

	@Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof VolumeMessage) {
            passThrough((VolumeMessage)msg);
        } else if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage)msg);
        } else {
            handleLocalMessage(msg);
        }
	}

	private void handleLocalMessage(Message msg) {
	   if (msg instanceof CreateVolumeMsg) {
           handle((CreateVolumeMsg) msg);
       } else if (msg instanceof VolumeReportPrimaryStorageCapacityUsageMsg) {
           handle((VolumeReportPrimaryStorageCapacityUsageMsg) msg);
	   } else {
		   bus.dealWithUnknownMessage(msg);
	   }
    }

    @Transactional(readOnly = true)
    private void handle(VolumeReportPrimaryStorageCapacityUsageMsg msg) {
        String sql = "select sum(vol.size) from VolumeVO vol where vol.primaryStorageUuid = :prUuid and vol.status = :status";
        TypedQuery<Long> q = dbf.getEntityManager().createQuery(sql, Long.class);
        q.setParameter("prUuid", msg.getPrimaryStorageUuid());
        q.setParameter("status", VolumeStatus.Ready);
        Long size = q.getSingleResult();

        VolumeReportPrimaryStorageCapacityUsageReply reply = new VolumeReportPrimaryStorageCapacityUsageReply();
        reply.setUsedCapacity(size == null ? 0 : size);
        bus.reply(msg, reply);
    }

    private VolumeInventory createVolume(CreateVolumeMsg msg) {
        VolumeVO vo = new VolumeVO();
        vo.setUuid(Platform.getUuid());
        vo.setRootImageUuid(msg.getRootImageUuid());
        vo.setDescription(msg.getDescription());
        vo.setName(msg.getName());
        vo.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        vo.setSize(msg.getSize());
        vo.setVmInstanceUuid(msg.getVmInstanceUuid());
        vo.setFormat(msg.getFormat());
        vo.setStatus(VolumeStatus.NotInstantiated);
        vo.setType(VolumeType.valueOf(msg.getVolumeType()));
        vo.setDiskOfferingUuid(msg.getDiskOfferingUuid());
        if (vo.getType() == VolumeType.Root) {
            vo.setDeviceId(0);
        }
        
        acntMgr.createAccountResourceRef(msg.getAccountUuid(), vo.getUuid(), VolumeVO.class);
        
        vo = dbf.persistAndRefresh(vo);
        VolumeInventory inv = VolumeInventory.valueOf(vo);
        logger.debug(String.format("successfully created volume[uuid:%s, name:%s, type:%s, vm uuid:%s",
                inv.getUuid(), inv.getName(), inv.getType(), inv.getVmInstanceUuid()));
        return inv;
    }
    
	private void handle(CreateVolumeMsg msg) {
		VolumeInventory inv = createVolume(msg);
		CreateVolumeReply reply = new CreateVolumeReply();
		reply.setInventory(inv);
		bus.reply(msg, reply);
    }


    private void handle(APICreateDataVolumeFromVolumeSnapshotMsg msg) {
        final APICreateDataVolumeFromVolumeSnapshotEvent evt = new APICreateDataVolumeFromVolumeSnapshotEvent(msg.getId());
        final VolumeVO vo = new VolumeVO();
        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setName(msg.getName());
        vo.setDescription(msg.getDescription());
        vo.setState(VolumeState.Enabled);
        vo.setStatus(VolumeStatus.Creating);
        vo.setType(VolumeType.Data);
        vo.setSize(0);
        dbf.persist(vo);

        acntMgr.createAccountResourceRef(msg.getSession().getAccountUuid(), vo.getUuid(), VolumeVO.class);
        tagMgr.createTagsFromAPICreateMessage(msg, vo.getUuid(), VolumeVO.class.getSimpleName());

        SimpleQuery<VolumeSnapshotVO> sq = dbf.createQuery(VolumeSnapshotVO.class);
        sq.select(VolumeSnapshotVO_.volumeUuid, VolumeSnapshotVO_.treeUuid);
        sq.add(VolumeSnapshotVO_.uuid, Op.EQ, msg.getVolumeSnapshotUuid());
        Tuple t = sq.findTuple();
        String volumeUuid = t.get(0, String.class);
        String treeUuid = t.get(1, String.class);

        CreateDataVolumeFromVolumeSnapshotMsg cmsg = new CreateDataVolumeFromVolumeSnapshotMsg();
        cmsg.setVolumeUuid(volumeUuid);
        cmsg.setTreeUuid(treeUuid);
        cmsg.setUuid(msg.getVolumeSnapshotUuid());
        cmsg.setVolume(VolumeInventory.valueOf(vo));
        cmsg.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        String resourceUuid = volumeUuid != null ? volumeUuid : treeUuid;
        bus.makeTargetServiceIdByResourceUuid(cmsg, VolumeSnapshotConstant.SERVICE_ID, resourceUuid);
        bus.send(cmsg, new CloudBusCallBack(msg) {
            @Override
            public void run(MessageReply reply) {
                if (reply.isSuccess()) {
                    CreateDataVolumeFromVolumeSnapshotReply cr = reply.castReply();
                    VolumeInventory inv = cr.getInventory();
                    vo.setSize(inv.getSize());
                    vo.setInstallPath(inv.getInstallPath());
                    vo.setStatus(VolumeStatus.Ready);
                    vo.setPrimaryStorageUuid(inv.getPrimaryStorageUuid());
                    vo.setFormat(inv.getFormat());
                    VolumeVO vvo = dbf.updateAndRefresh(vo);
                    evt.setInventory(VolumeInventory.valueOf(vvo));
                } else {
                    evt.setErrorCode(reply.getError());
                }

                bus.publish(evt);
            }
        });
    }

	private void handleApiMessage(APIMessage msg) {
		if (msg instanceof APICreateDataVolumeMsg) {
            handle((APICreateDataVolumeMsg) msg);
		} else if (msg instanceof APIListVolumeMsg) {
			handle((APIListVolumeMsg)msg);
		} else if (msg instanceof APISearchVolumeMsg) {
		    handle((APISearchVolumeMsg) msg);
		} else if (msg instanceof APIGetVolumeMsg) {
		    handle((APIGetVolumeMsg) msg);
        } else if (msg instanceof APICreateDataVolumeFromVolumeSnapshotMsg) {
            handle((APICreateDataVolumeFromVolumeSnapshotMsg) msg);
        } else if (msg instanceof APICreateDataVolumeFromVolumeTemplateMsg) {
            handle((APICreateDataVolumeFromVolumeTemplateMsg) msg);
        } else if (msg instanceof APIGetVolumeFormatMsg) {
            handle((APIGetVolumeFormatMsg) msg);
		} else {
			bus.dealWithUnknownMessage(msg);
		}
    }

    private void handle(APIGetVolumeFormatMsg msg) {
        List<VolumeFormatReplyStruct> structs = new ArrayList<VolumeFormatReplyStruct>();
        for (VolumeFormat format : VolumeFormat.getAllFormats()) {
            structs.add(new VolumeFormatReplyStruct(format));
        }

        APIGetVolumeFormatReply reply = new APIGetVolumeFormatReply();
        reply.setFormats(structs);
        bus.reply(msg, reply);
    }

    private void handle(final APICreateDataVolumeFromVolumeTemplateMsg msg) {
        final APICreateDataVolumeFromVolumeTemplateEvent evt = new APICreateDataVolumeFromVolumeTemplateEvent(msg.getId());

        final ImageVO template = dbf.findByUuid(msg.getImageUuid(), ImageVO.class);
        final VolumeVO vol = new VolumeVO();
        vol.setUuid(msg.getResourceUuid() == null ? Platform.getUuid() : msg.getResourceUuid());
        vol.setName(msg.getName());
        vol.setDescription(msg.getDescription());
        vol.setFormat(template.getFormat());
        vol.setSize(template.getSize());
        vol.setRootImageUuid(template.getUuid());
        vol.setStatus(VolumeStatus.Creating);
        vol.setState(VolumeState.Enabled);
        vol.setType(VolumeType.Data);
        vol.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        dbf.persist(vol);

        acntMgr.createAccountResourceRef(msg.getSession().getAccountUuid(), vol.getUuid(), VolumeVO.class);
        tagMgr.createTagsFromAPICreateMessage(msg, vol.getUuid(), VolumeVO.class.getSimpleName());

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("create-data-volume-from-template-%s", template.getUuid()));
        chain.then(new ShareFlow() {
            ImageBackupStorageRefVO targetBackupStorageRef;
            PrimaryStorageInventory targetPrimaryStorage;
            String primaryStorageInstallPath;
            String volumeFormat;

            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "select-backup-storage";

                    @Override
                    @Transactional(readOnly = true)
                    public void run(FlowTrigger trigger, Map data) {
                        List<String> bsUuids = CollectionUtils.transformToList(template.getBackupStorageRefs(), new Function<String, ImageBackupStorageRefVO>() {
                            @Override
                            public String call(ImageBackupStorageRefVO arg) {
                                return arg.getBackupStorageUuid();
                            }
                        });

                        String sql = "select bs.uuid from BackupStorageVO bs, BackupStorageZoneRefVO zref, PrimaryStorageVO ps where zref.zoneUuid = ps.zoneUuid and bs.status = :bsStatus and bs.state = :bsState and ps.uuid = :psUuid and zref.backupStorageUuid = bs.uuid and bs.uuid in (:bsUuids)";
                        TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                        q.setParameter("psUuid", msg.getPrimaryStorageUuid());
                        q.setParameter("bsStatus", BackupStorageStatus.Connected);
                        q.setParameter("bsState", BackupStorageState.Enabled);
                        q.setParameter("bsUuids", bsUuids);
                        bsUuids = q.getResultList();

                        if (bsUuids.isEmpty()) {
                            trigger.fail(errf.stringToOperationError(
                                    String.format("cannot find a backup storage on which the image[uuid:%s] is that satisfies all conditions of: 1. has state Enabled 2. has status Connected. 3 has attached to zone in which primary storage[uuid:%s] is",
                                            template.getUuid(), msg.getPrimaryStorageUuid())
                            ));
                            return;
                        }

                        final String bsUuid = bsUuids.get(0);
                        targetBackupStorageRef = CollectionUtils.find(template.getBackupStorageRefs(), new Function<ImageBackupStorageRefVO, ImageBackupStorageRefVO>() {
                            @Override
                            public ImageBackupStorageRefVO call(ImageBackupStorageRefVO arg) {
                                return arg.getBackupStorageUuid().equals(bsUuid) ? arg : null;
                            }
                        });
                        trigger.next();
                    }
                });

                flow(new Flow() {
                    String __name__ = "allocate-primary-storage";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        AllocatePrimaryStorageMsg amsg = new AllocatePrimaryStorageMsg();
                        amsg.setSize(template.getSize());
                        amsg.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
                        bus.makeLocalServiceId(amsg, PrimaryStorageConstant.SERVICE_ID);
                        bus.send(amsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                } else {
                                    targetPrimaryStorage = ((AllocatePrimaryStorageReply)reply).getPrimaryStorageInventory();
                                    trigger.next();
                                }
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowTrigger trigger, Map data) {
                        if (targetPrimaryStorage != null) {
                            ReturnPrimaryStorageCapacityMsg rmsg = new ReturnPrimaryStorageCapacityMsg();
                            rmsg.setDiskSize(template.getSize());
                            rmsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                            bus.makeTargetServiceIdByResourceUuid(rmsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());;
                            bus.send(rmsg);
                        }
                        trigger.rollback();
                    }
                });

                flow(new Flow() {
                    String __name__ = "download-data-volume-template-to-primary-storage";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        DownloadDataVolumeToPrimaryStorageMsg dmsg = new DownloadDataVolumeToPrimaryStorageMsg();
                        dmsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                        dmsg.setVolumeUuid(vol.getUuid());
                        dmsg.setBackupStorageRef(ImageBackupStorageRefInventory.valueOf(targetBackupStorageRef));
                        dmsg.setImage(ImageInventory.valueOf(template));
                        bus.makeTargetServiceIdByResourceUuid(dmsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());
                        bus.send(dmsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                } else {
                                    DownloadDataVolumeToPrimaryStorageReply r = reply.castReply();
                                    primaryStorageInstallPath = r.getInstallPath();
                                    volumeFormat = r.getFormat();
                                    trigger.next();
                                }
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowTrigger trigger, Map data) {
                        if (primaryStorageInstallPath != null) {
                            DeleteBitsOnPrimaryStorageMsg delMsg = new DeleteBitsOnPrimaryStorageMsg();
                            delMsg.setInstallPath(PathUtil.parentFolder(primaryStorageInstallPath));
                            delMsg.setFolder(true);
                            delMsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                            delMsg.setHypervisorType(VolumeFormat.getMasterHypervisorTypeByVolumeFormat(vol.getFormat()).toString());
                            bus.makeTargetServiceIdByResourceUuid(delMsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());
                            bus.send(delMsg);
                        }
                        trigger.rollback();
                    }
                });

                done(new FlowDoneHandler(msg) {
                    @Override
                    public void handle(Map data) {
                        vol.setInstallPath(primaryStorageInstallPath);
                        vol.setStatus(VolumeStatus.Ready);
                        if (volumeFormat != null) {
                            vol.setFormat(volumeFormat);
                        }
                        VolumeVO vo = dbf.updateAndRefresh(vol);
                        evt.setInventory(VolumeInventory.valueOf(vo));
                        bus.publish(evt);
                    }
                });

                error(new FlowErrorHandler(msg) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        evt.setErrorCode(errCode);
                        bus.publish(evt);
                    }
                });
            }
        }).start();
    }

    private void handle(APIGetVolumeMsg msg) {
	    SearchQuery<VolumeInventory> q = new SearchQuery(VolumeInventory.class);
	    q.addAccountAsAnd(msg);
	    q.add("uuid", SearchOp.AND_EQ, msg.getUuid());
	    List<VolumeInventory> invs = q.list();
	    APIGetVolumeReply reply = new APIGetVolumeReply();
	    if (!invs.isEmpty()) {
	        reply.setInventory(JSONObjectUtil.toJsonString(invs.get(0)));
	    }
	    bus.reply(msg, reply);
    }

    private void handle(APISearchVolumeMsg msg) {
	    SearchQuery<VolumeInventory> q = SearchQuery.create(msg, VolumeInventory.class);
	    q.addAccountAsAnd(msg);
	    String res = q.listAsString();
	    APISearchVolumeReply reply = new APISearchVolumeReply();
	    reply.setContent(res);
	    bus.reply(msg, reply);
    }

    private void handle(APIListVolumeMsg msg) {
		List<VolumeVO> vos = dl.listByApiMessage(msg, VolumeVO.class);
		List<VolumeInventory> invs = VolumeInventory.valueOf(vos);
		APIListVolumeReply reply = new APIListVolumeReply();
		reply.setInventories(invs);
		bus.reply(msg, reply);
    }


	private void handle(APICreateDataVolumeMsg msg) {
		APICreateDataVolumeEvent evt = new APICreateDataVolumeEvent(msg.getId());
		DiskOfferingVO dvo = dbf.findByUuid(msg.getDiskOfferingUuid(), DiskOfferingVO.class);

		VolumeVO vo = new VolumeVO();
        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setDiskOfferingUuid(dvo.getUuid());
		vo.setDescription(msg.getDescription());
		vo.setName(msg.getName());
		vo.setSize(dvo.getDiskSize());
		vo.setType(VolumeType.Data);
        vo.setStatus(VolumeStatus.NotInstantiated);

		acntMgr.createAccountResourceRef(msg.getSession().getAccountUuid(), vo.getUuid(), VolumeVO.class);
        tagMgr.createTagsFromAPICreateMessage(msg, vo.getUuid(), VolumeVO.class.getSimpleName());
		
		vo = dbf.persistAndRefresh(vo);
		VolumeInventory inv = VolumeInventory.valueOf(vo);
		evt.setInventory(inv);
        logger.debug(String.format("Successfully created data volume[name:%s, uuid:%s, size:%s]", inv.getName(), inv.getUuid(), inv.getSize()));
		bus.publish(evt);
    }

	@Override
	public String getId() {
		return bus.makeLocalServiceId(VolumeConstant.SERVICE_ID);
	}

	@Override
	public boolean start() {
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}
}
