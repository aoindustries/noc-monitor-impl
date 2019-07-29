/*
 * Copyright 2009-2013, 2014, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.mysql;

import com.aoindustries.aoserv.client.backup.MysqlReplication;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.noc.monitor.AlertLevelUtils;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.TableResultNodeImpl;
import com.aoindustries.noc.monitor.common.AlertLevel;
import java.io.File;
import java.io.IOException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.sql.SQLException;
import java.util.List;

/**
 * The node for one Database.
 *
 * @author  AO Industries, Inc.
 */
public class DatabaseNode extends TableResultNodeImpl {

	private static final long serialVersionUID = 1L;

	final DatabaseNodeWorker databaseWorker;
	final DatabasesNode mysqlDatabasesNode;
	final Database mysqlDatabase;
	private final MysqlReplication mysqlSlave;
	private final Database.Name _label;

	private boolean started;

	volatile private CheckTablesNode mysqlCheckTablesNode;

	DatabaseNode(DatabasesNode mysqlDatabasesNode, Database mysqlDatabase, MysqlReplication mysqlSlave, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws IOException, SQLException {
		super(
			mysqlDatabasesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode,
			mysqlDatabasesNode,
			DatabaseNodeWorker.getWorker(
				new File(mysqlDatabasesNode.getPersistenceDirectory(), mysqlDatabase.getName()+".show_full_tables"),
				mysqlDatabase,
				mysqlSlave
			),
			port,
			csf,
			ssf
		);
		this.databaseWorker = (DatabaseNodeWorker)worker;
		this.mysqlDatabasesNode = mysqlDatabasesNode;
		this.mysqlDatabase = mysqlDatabase;
		this.mysqlSlave = mysqlSlave;
		this._label = mysqlDatabase.getName();
	}

	Database getMySQLDatabase() {
		return mysqlDatabase;
	}

	MysqlReplication getMySQLSlave() {
		return mysqlSlave;
	}

	@Override
	public boolean getAllowsChildren() {
		return true;
	}

	@Override
	public List<CheckTablesNode> getChildren() {
		return getSnapshot(this.mysqlCheckTablesNode);
	}

	/**
	 * The alert level is equal to the highest alert level of its children.
	 */
	@Override
	public AlertLevel getAlertLevel() {
		return constrainAlertLevel(
			AlertLevelUtils.getMaxAlertLevel(
				super.getAlertLevel(),
				this.mysqlCheckTablesNode
			)
		);
	}

	@Override
	public String getLabel() {
		return _label.toString();
	}

	File getPersistenceDirectory() throws IOException {
		File dir = new File(mysqlDatabasesNode.getPersistenceDirectory(), _label.toString());
		if(!dir.exists()) {
			if(!dir.mkdir()) {
				throw new IOException(
					accessor.getMessage(
						mysqlDatabasesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode.locale,
						"error.mkdirFailed",
						dir.getCanonicalPath()
					)
				);
			}
		}
		return dir;
	}

	@Override
	public void start() throws IOException {
		synchronized(this) {
			if(started) throw new IllegalStateException();
			started = true;
			if(mysqlCheckTablesNode==null) {
				mysqlCheckTablesNode = new CheckTablesNode(this, port, csf, ssf);
				mysqlCheckTablesNode.start();
				mysqlDatabasesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode.nodeAdded();
			}
			super.start();
		}
	}

	@Override
	public void stop() {
		synchronized(this) {
			started = false;
			super.stop();
			if(mysqlCheckTablesNode!=null) {
				mysqlCheckTablesNode.stop();
				mysqlCheckTablesNode = null;
				mysqlDatabasesNode.mysqlServerNode._mysqlServersNode.hostNode.hostsNode.rootNode.nodeRemoved();
			}
		}
	}
}