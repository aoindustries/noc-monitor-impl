/*
 * Copyright 2008-2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.noc.monitor.common.AlertLevel;
import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The node for RAID devices.
 *
 * @author  AO Industries, Inc.
 */
public class RaidNode extends NodeImpl {

	private static final long serialVersionUID = 1L;

    final ServerNode serverNode;
    private final AOServer aoServer;

    volatile private ThreeWareRaidNode _threeWareRaidNode;
    volatile private MdStatNode _mdStatNode;
    volatile private MdMismatchNode _mdMismatchNode;
    volatile private DrbdNode _drbdNode;

    RaidNode(ServerNode serverNode, AOServer aoServer, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException {
        super(port, csf, ssf);
        this.serverNode = serverNode;
        this.aoServer = aoServer;
    }

    @Override
    public ServerNode getParent() {
        return serverNode;
    }

    public AOServer getAOServer() {
        return aoServer;
    }

    @Override
    public boolean getAllowsChildren() {
        return true;
    }

    /**
     * For thread safety and encapsulation, returns an unmodifiable copy of the array.
     */
    @Override
    public List<NodeImpl> getChildren() {
        List<NodeImpl> children = new ArrayList<NodeImpl>();
        ThreeWareRaidNode threeWareRaidNode = this._threeWareRaidNode;
        if(threeWareRaidNode!=null) children.add(threeWareRaidNode);
        MdStatNode mdStatNode = this._mdStatNode;
        if(mdStatNode!=null) children.add(mdStatNode);
        MdMismatchNode mdMismatchNode = this._mdMismatchNode;
        if(mdMismatchNode!=null) children.add(mdMismatchNode);
        DrbdNode drbdNode = this._drbdNode;
        if(drbdNode!=null) children.add(drbdNode);
        return Collections.unmodifiableList(children);
    }

    /**
     * The alert level is equal to the highest alert level of its children.
     */
    @Override
    public AlertLevel getAlertLevel() {
        AlertLevel level = AlertLevel.NONE;

        ThreeWareRaidNode threeWareRaidNode = this._threeWareRaidNode;
        if(threeWareRaidNode!=null) {
            AlertLevel threeWareRaidNodeLevel = threeWareRaidNode.getAlertLevel();
            if(threeWareRaidNodeLevel.compareTo(level)>0) level = threeWareRaidNodeLevel;
        }
        MdStatNode mdStatNode = this._mdStatNode;
        if(mdStatNode!=null) {
            AlertLevel mdStatNodeLevel = mdStatNode.getAlertLevel();
            if(mdStatNodeLevel.compareTo(level)>0) level = mdStatNodeLevel;
        }
        MdMismatchNode mdMismatchNode = this._mdMismatchNode;
        if(mdMismatchNode!=null) {
            AlertLevel mdMismatchNodeLevel = mdMismatchNode.getAlertLevel();
            if(mdMismatchNodeLevel.compareTo(level)>0) level = mdMismatchNodeLevel;
        }
        DrbdNode drbdNode = this._drbdNode;
        if(drbdNode!=null) {
            AlertLevel drbdNodeLevel = drbdNode.getAlertLevel();
            if(drbdNodeLevel.compareTo(level)>0) level = drbdNodeLevel;
        }
        return level;
    }

    /**
     * No alert messages.
     */
    @Override
    public String getAlertMessage() {
        return null;
    }

    @Override
    public String getLabel() {
        return accessor.getMessage(/*serverNode.serversNode.rootNode.locale,*/ "RaidNode.label");
    }
    
    synchronized void start() throws IOException, SQLException {
        // We only have 3ware cards in xen outers
        int osv = aoServer.getServer().getOperatingSystemVersion().getPkey();
        if(
            osv==OperatingSystemVersion.CENTOS_5_DOM0_I686
            || osv==OperatingSystemVersion.CENTOS_5_DOM0_X86_64
        ) {
            if(_threeWareRaidNode==null) {
                _threeWareRaidNode = new ThreeWareRaidNode(this, port, csf, ssf);
                _threeWareRaidNode.start();
                serverNode.serversNode.rootNode.nodeAdded();
            }
        }
        // Any machine may have MD RAID (at least until all services run in Xen outers)
        if(_mdStatNode==null) {
            _mdStatNode = new MdStatNode(this, port, csf, ssf);
            _mdStatNode.start();
            serverNode.serversNode.rootNode.nodeAdded();
        }
        if(_mdMismatchNode==null) {
            _mdMismatchNode = new MdMismatchNode(this, port, csf, ssf);
            _mdMismatchNode.start();
            serverNode.serversNode.rootNode.nodeAdded();
        }
        // We only run DRBD in xen outers
        if(
            osv==OperatingSystemVersion.CENTOS_5_DOM0_I686
            || osv==OperatingSystemVersion.CENTOS_5_DOM0_X86_64
        ) {
            if(_drbdNode==null) {
                _drbdNode = new DrbdNode(this, port, csf, ssf);
                _drbdNode.start();
                serverNode.serversNode.rootNode.nodeAdded();
            }
        }
    }

    synchronized void stop() {
        if(_threeWareRaidNode!=null) {
            _threeWareRaidNode.stop();
            _threeWareRaidNode = null;
            serverNode.serversNode.rootNode.nodeRemoved();
        }
        if(_mdStatNode!=null) {
            _mdStatNode.stop();
            _mdStatNode = null;
            serverNode.serversNode.rootNode.nodeRemoved();
        }
        if(_mdMismatchNode!=null) {
            _mdMismatchNode.stop();
            _mdMismatchNode = null;
            serverNode.serversNode.rootNode.nodeRemoved();
        }
        if(_drbdNode!=null) {
            _drbdNode.stop();
            _drbdNode = null;
            serverNode.serversNode.rootNode.nodeRemoved();
        }
    }

    File getPersistenceDirectory() throws IOException {
        File dir = new File(serverNode.getPersistenceDirectory(), "raid");
        if(!dir.exists()) {
            if(!dir.mkdir()) {
                throw new IOException(
                    accessor.getMessage(
                        //serverNode.serversNode.rootNode.locale,
                        "error.mkdirFailed",
                        dir.getCanonicalPath()
                    )
                );
            }
        }
        return dir;
    }
}
