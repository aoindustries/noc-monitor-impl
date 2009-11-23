/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.noc.common.AlertLevel;
import com.aoindustries.noc.common.Node;
import com.aoindustries.table.Table;
import com.aoindustries.table.TableListener;
import com.aoindustries.util.WrappedException;
import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * The node per server.
 *
 * @author  AO Industries, Inc.
 */
public class ServerNode extends NodeImpl {

    private static final long serialVersionUID = 1L;

    final ServersNode serversNode;
    private final Server _server;
    private final int _pack;
    private final String _name;
    private final String _label;

    volatile private BackupsNode _backupsNode;
    volatile private NetDevicesNode _netDevicesNode;
    volatile private MySQLServersNode _mysqlServersNode;
    volatile private HardDrivesNode _hardDrivesNode;
    volatile private RaidNode _raidNode;
    volatile private FilesystemsNode _filesystemsNode;
    volatile private LoadAverageNode _loadAverageNode;
    volatile private MemoryNode _memoryNode;
    volatile private TimeNode _timeNode;

    ServerNode(ServersNode serversNode, Server server, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException, IOException, SQLException {
        super(port, csf, ssf);
        this.serversNode = serversNode;
        this._server = server;
        this._pack = server.getPackageId();
        this._name = server.getName();
        this._label = server.toString();
    }

    @Override
    public Node getParent() {
        return serversNode;
    }

    public Server getServer() {
        return _server;
    }

    @Override
    public boolean getAllowsChildren() {
        return true;
    }

    /**
     * For thread safety and encapsulation, returns an unmodifiable copy of the array.
     */
    @Override
    public List<? extends Node> getChildren() {
        List<NodeImpl> children = new ArrayList<NodeImpl>(9);
        BackupsNode backupsNode = this._backupsNode;
        if(backupsNode!=null) children.add(backupsNode);
        NetDevicesNode netDevicesNode = this._netDevicesNode;
        if(netDevicesNode!=null) children.add(netDevicesNode);
        MySQLServersNode mysqlServersNode = this._mysqlServersNode;
        if(mysqlServersNode!=null) children.add(mysqlServersNode);
        HardDrivesNode hardDrivesNode = this._hardDrivesNode;
        if(hardDrivesNode!=null) children.add(hardDrivesNode);
        RaidNode raidNode = this._raidNode;
        if(raidNode!=null) children.add(raidNode);
        FilesystemsNode filesystemsNode = this._filesystemsNode;
        if(filesystemsNode!=null) children.add(filesystemsNode);
        LoadAverageNode loadAverageNode = this._loadAverageNode;
        if(loadAverageNode!=null) children.add(loadAverageNode);
        MemoryNode memoryNode = this._memoryNode;
        if(memoryNode!=null) children.add(memoryNode);
        TimeNode timeNode = this._timeNode;
        if(timeNode!=null) children.add(timeNode);
        return Collections.unmodifiableList(children);
    }

    /**
     * The alert level is equal to the highest alert level of its children.
     */
    @Override
    public AlertLevel getAlertLevel() {
        AlertLevel level = AlertLevel.NONE;

        BackupsNode backupsNode = this._backupsNode;
        if(backupsNode!=null) {
            AlertLevel backupsNodeLevel = backupsNode.getAlertLevel();
            if(backupsNodeLevel.compareTo(level)>0) level = backupsNodeLevel;
        }
        NetDevicesNode netDevicesNode = this._netDevicesNode;
        if(netDevicesNode!=null) {
            AlertLevel netDevicesNodeLevel = netDevicesNode.getAlertLevel();
            if(netDevicesNodeLevel.compareTo(level)>0) level = netDevicesNodeLevel;
        }
        MySQLServersNode mysqlServersNode = this._mysqlServersNode;
        if(mysqlServersNode!=null) {
            AlertLevel mysqlServersNodeLevel = mysqlServersNode.getAlertLevel();
            if(mysqlServersNodeLevel.compareTo(level)>0) level = mysqlServersNodeLevel;
        }
        HardDrivesNode hardDrivesNode = this._hardDrivesNode;
        if(hardDrivesNode!=null) {
            AlertLevel hardDrivesNodeLevel = hardDrivesNode.getAlertLevel();
            if(hardDrivesNodeLevel.compareTo(level)>0) level = hardDrivesNodeLevel;
        }
        RaidNode raidNode = this._raidNode;
        if(raidNode!=null) {
            AlertLevel raidNodeLevel = raidNode.getAlertLevel();
            if(raidNodeLevel.compareTo(level)>0) level = raidNodeLevel;
        }
        FilesystemsNode filesystemsNode = this._filesystemsNode;
        if(filesystemsNode!=null) {
            AlertLevel filesystemsNodeLevel = filesystemsNode.getAlertLevel();
            if(filesystemsNodeLevel.compareTo(level)>0) level = filesystemsNodeLevel;
        }
        LoadAverageNode loadAverageNode = this._loadAverageNode;
        if(loadAverageNode!=null) {
            AlertLevel loadAverageNodeLevel = loadAverageNode.getAlertLevel();
            if(loadAverageNodeLevel.compareTo(level)>0) level = loadAverageNodeLevel;
        }
        MemoryNode memoryNode = this._memoryNode;
        if(memoryNode!=null) {
            AlertLevel memoryNodeLevel = memoryNode.getAlertLevel();
            if(memoryNodeLevel.compareTo(level)>0) level = memoryNodeLevel;
        }
        TimeNode timeNode = this._timeNode;
        if(timeNode!=null) {
            AlertLevel timeNodeLevel = timeNode.getAlertLevel();
            if(timeNodeLevel.compareTo(level)>0) level = timeNodeLevel;
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
        return _label;
    }

    private TableListener tableListener = new TableListener() {
        @Override
        public void tableUpdated(Table table) {
            try {
                verifyNetDevices();
                verifyMySQLServers();
                verifyHardDrives();
                verifyRaid();
                verifyFilesystems();
                verifyLoadAverage();
                verifyMemory();
                verifyTime();
            } catch(IOException err) {
                throw new WrappedException(err);
            } catch(SQLException err) {
                throw new WrappedException(err);
            }
        }
    };

    /**
     * Starts this node after it is added to the parent.
     */
    synchronized void start() throws IOException, SQLException {
        serversNode.rootNode.conn.getAoServers().addTableListener(tableListener, 100);
        serversNode.rootNode.conn.getNetDevices().addTableListener(tableListener, 100);
        serversNode.rootNode.conn.getMysqlServers().addTableListener(tableListener, 100);
        serversNode.rootNode.conn.getServers().addTableListener(tableListener, 100);
        if(_backupsNode==null) {
            _backupsNode = new BackupsNode(this, port, csf, ssf);
            _backupsNode.start();
            serversNode.rootNode.nodeAdded();
        }
        verifyNetDevices();
        verifyMySQLServers();
        verifyHardDrives();
        verifyRaid();
        verifyFilesystems();
        verifyLoadAverage();
        verifyMemory();
        verifyTime();
    }

    /**
     * Stops this node before it is removed from the parent.
     */
    synchronized void stop() {
        serversNode.rootNode.conn.getAoServers().removeTableListener(tableListener);
        serversNode.rootNode.conn.getNetDevices().removeTableListener(tableListener);
        serversNode.rootNode.conn.getMysqlServers().removeTableListener(tableListener);
        serversNode.rootNode.conn.getServers().removeTableListener(tableListener);
        if(_timeNode!=null) {
            _timeNode.stop();
            _timeNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_memoryNode!=null) {
            _memoryNode.stop();
            _memoryNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_loadAverageNode!=null) {
            _loadAverageNode.stop();
            _loadAverageNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_filesystemsNode!=null) {
            _filesystemsNode.stop();
            _filesystemsNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_raidNode!=null) {
            _raidNode.stop();
            _raidNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_hardDrivesNode!=null) {
            _hardDrivesNode.stop();
            _hardDrivesNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_mysqlServersNode!=null) {
            _mysqlServersNode.stop();
            _mysqlServersNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_netDevicesNode!=null) {
            _netDevicesNode.stop();
            _netDevicesNode = null;
            serversNode.rootNode.nodeRemoved();
        }
        if(_backupsNode!=null) {
            _backupsNode.stop();
            _backupsNode = null;
            serversNode.rootNode.nodeRemoved();
        }
    }

    synchronized private void verifyNetDevices() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        if(_netDevicesNode==null) {
            _netDevicesNode = new NetDevicesNode(this, _server, port, csf, ssf);
            _netDevicesNode.start();
            serversNode.rootNode.nodeAdded();
        }
    }

    synchronized private void verifyMySQLServers() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        List<MySQLServer> mysqlServers = aoServer==null ? null : aoServer.getMySQLServers();
        if(mysqlServers!=null && !mysqlServers.isEmpty()) {
            // Has MySQL server
            if(_mysqlServersNode==null) {
                _mysqlServersNode = new MySQLServersNode(this, aoServer, port, csf, ssf);
                _mysqlServersNode.start();
                serversNode.rootNode.nodeAdded();
            }
        } else {
            // No MySQL server
            if(_mysqlServersNode!=null) {
                _mysqlServersNode.stop();
                _mysqlServersNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        }
    }

    synchronized private void verifyHardDrives() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        OperatingSystemVersion osvObj = _server.getOperatingSystemVersion();
        int osv = osvObj==null ? -1 : osvObj.getPkey();
        if(
            aoServer!=null
            && (
                osv==OperatingSystemVersion.CENTOS_5DOM0_I686
                || osv==OperatingSystemVersion.CENTOS_5DOM0_X86_64
            )
        ) {
            // Has hddtemp monitoring
            if(_hardDrivesNode==null) {
                _hardDrivesNode = new HardDrivesNode(this, aoServer, port, csf, ssf);
                _hardDrivesNode.start();
                serversNode.rootNode.nodeAdded();
            }
        } else {
            // No hddtemp monitoring
            if(_hardDrivesNode!=null) {
                _hardDrivesNode.stop();
                _hardDrivesNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        }
    }

    synchronized private void verifyRaid() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        if(aoServer==null) {
            // No raid monitoring
            if(_raidNode!=null) {
                _raidNode.stop();
                _raidNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        } else {
            // Has raid monitoring
            if(_raidNode==null) {
                _raidNode = new RaidNode(this, aoServer, port, csf, ssf);
                _raidNode.start();
                serversNode.rootNode.nodeAdded();
            }
        }
    }

    synchronized private void verifyFilesystems() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        if(aoServer==null) {
            // No filesystem monitoring
            if(_filesystemsNode!=null) {
                _filesystemsNode.stop();
                _filesystemsNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        } else {
            // Has filesystem monitoring
            if(_filesystemsNode==null) {
                _filesystemsNode = new FilesystemsNode(this, aoServer, port, csf, ssf);
                _filesystemsNode.start();
                serversNode.rootNode.nodeAdded();
            }
        }
    }

    synchronized private void verifyLoadAverage() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        if(aoServer==null) {
            // No load monitoring
            if(_loadAverageNode!=null) {
                _loadAverageNode.stop();
                _loadAverageNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        } else {
            // Has load monitoring
            if(_loadAverageNode==null) {
                _loadAverageNode = new LoadAverageNode(this, aoServer, port, csf, ssf);
                _loadAverageNode.start();
                serversNode.rootNode.nodeAdded();
            }
        }
    }

    synchronized private void verifyMemory() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        if(aoServer==null) {
            // No memory monitoring
            if(_memoryNode!=null) {
                _memoryNode.stop();
                _memoryNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        } else {
            // Has memory monitoring
            if(_memoryNode==null) {
                _memoryNode = new MemoryNode(this, aoServer, port, csf, ssf);
                _memoryNode.start();
                serversNode.rootNode.nodeAdded();
            }
        }
    }

    synchronized private void verifyTime() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        AOServer aoServer = _server.getAOServer();
        if(aoServer == null) {
            // No time monitoring
            if(_timeNode!=null) {
                _timeNode.stop();
                _timeNode = null;
                serversNode.rootNode.nodeRemoved();
            }
        } else {
            // Has time monitoring
            if(_timeNode==null) {
                _timeNode = new TimeNode(this, aoServer, port, csf, ssf);
                _timeNode.start();
                serversNode.rootNode.nodeAdded();
            }
        }
    }

    File getPersistenceDirectory() throws IOException {
        File packDir = new File(serversNode.getPersistenceDirectory(), Integer.toString(_pack));
        if(!packDir.exists()) {
            if(!packDir.mkdir()) {
                throw new IOException(
                    ApplicationResourcesAccessor.getMessage(
                        serversNode.rootNode.locale,
                        "error.mkdirFailed",
                        packDir.getCanonicalPath()
                    )
                );
            }
        }
        File serverDir = new File(packDir, _name);
        if(!serverDir.exists()) {
            if(!serverDir.mkdir()) {
                throw new IOException(
                    ApplicationResourcesAccessor.getMessage(
                        serversNode.rootNode.locale,
                        "error.mkdirFailed",
                        serverDir.getCanonicalPath()
                    )
                );
            }
        }
        return serverDir;
    }
}
