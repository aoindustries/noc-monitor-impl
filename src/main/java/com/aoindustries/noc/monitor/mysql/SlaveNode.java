/*
 * Copyright 2009, 2014, 2016, 2018, 2019, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.mysql;

import com.aoindustries.aoserv.client.backup.BackupPartition;
import com.aoindustries.aoserv.client.backup.FileReplication;
import com.aoindustries.aoserv.client.backup.MysqlReplication;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.noc.monitor.AlertLevelUtils;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.NodeImpl;
import com.aoindustries.noc.monitor.RootNodeImpl;
import com.aoindustries.noc.monitor.common.AlertLevel;
import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.sql.SQLException;
import java.util.List;

/**
 * The node for all FailoverMySQLReplications on one Server.
 *
 * @author  AO Industries, Inc.
 */
public class SlaveNode extends NodeImpl {

	private static final long serialVersionUID = 1L;

	final SlavesNode mysqlSlavesNode;
	private final MysqlReplication _mysqlReplication;
	private final String _label;

	private boolean started;

	volatile private SlaveStatusNode _mysqlSlaveStatusNode;
	volatile private DatabasesNode _mysqlDatabasesNode;

	SlaveNode(SlavesNode mysqlSlavesNode, MysqlReplication mysqlReplication, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException, IOException, SQLException {
		super(port, csf, ssf);
		this.mysqlSlavesNode = mysqlSlavesNode;
		this._mysqlReplication = mysqlReplication;
		FileReplication replication = mysqlReplication.getFailoverFileReplication();
		if(replication!=null) {
			// replication-based
			com.aoindustries.aoserv.client.linux.Server linuxServer = mysqlSlavesNode.mysqlServerNode._mysqlServersNode.getAOServer();
			Server mysqlServer = mysqlSlavesNode.mysqlServerNode.getMySQLServer();
			BackupPartition bp = mysqlReplication.getFailoverFileReplication().getBackupPartition();
			this._label = bp.getLinuxServer().getHostname()+":"+bp.getPath()+"/"+linuxServer.getHostname()+"/var/lib/mysql/"+mysqlServer.getName();
		} else {
			// ao_server-based
			Server mysqlServer = mysqlSlavesNode.mysqlServerNode.getMySQLServer();
			this._label = mysqlReplication.getLinuxServer().getHostname()+":/var/lib/mysql/"+mysqlServer.getName();
		}
	}

	MysqlReplication getFailoverMySQLReplication() {
		return _mysqlReplication;
	}

	@Override
	public SlavesNode getParent() {
		return mysqlSlavesNode;
	}

	@Override
	public boolean getAllowsChildren() {
		return true;
	}

	@Override
	public List<NodeImpl> getChildren() {
		return getSnapshot(
			this._mysqlSlaveStatusNode,
			this._mysqlDatabasesNode
		);
	}

	/**
	 * The maximum alert level is constrained by the failover_mysql_replications table.
	 */
	@Override
	protected AlertLevel getMaxAlertLevel() {
		return AlertLevelUtils.getMonitoringAlertLevel(
			_mysqlReplication.getMaxAlertLevel()
		);
	}

	/**
	 * The alert level is equal to the highest alert level of its children.
	 */
	@Override
	public AlertLevel getAlertLevel() {
		return constrainAlertLevel(
			AlertLevelUtils.getMaxAlertLevel(
				this._mysqlSlaveStatusNode,
				this._mysqlDatabasesNode
			)
		);
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

	void start() throws IOException, SQLException {
		RootNodeImpl rootNode = mysqlSlavesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode;
		synchronized(this) {
			if(started) throw new IllegalStateException();
			started = true;
			if(_mysqlSlaveStatusNode==null) {
				_mysqlSlaveStatusNode = new SlaveStatusNode(this, port, csf, ssf);
				_mysqlSlaveStatusNode.start();
				rootNode.nodeAdded();
			}
			if(_mysqlDatabasesNode==null) {
				_mysqlDatabasesNode = new DatabasesNode(this, port, csf, ssf);
				_mysqlDatabasesNode.start();
				rootNode.nodeAdded();
			}
		}
	}

	void stop() {
		RootNodeImpl rootNode = mysqlSlavesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode;
		synchronized(this) {
			started = false;
			if(_mysqlSlaveStatusNode!=null) {
				_mysqlSlaveStatusNode.stop();
				_mysqlSlaveStatusNode = null;
				rootNode.nodeRemoved();
			}

			if(_mysqlDatabasesNode!=null) {
				_mysqlDatabasesNode.stop();
				_mysqlDatabasesNode = null;
				rootNode.nodeRemoved();
			}
		}
	}

	File getPersistenceDirectory() throws IOException {
		File dir = new File(mysqlSlavesNode.getPersistenceDirectory(), Integer.toString(_mysqlReplication.getPkey()));
		if(!dir.exists()) {
			if(!dir.mkdir()) {
				throw new IOException(
					accessor.getMessage(
						mysqlSlavesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode.locale,
						"error.mkdirFailed",
						dir.getCanonicalPath()
					)
				);
			}
		}
		return dir;
	}
}
