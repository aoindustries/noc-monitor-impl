/*
 * Copyright 2009, 2014, 2016, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.mysql;

import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.noc.monitor.AlertLevelUtils;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.NodeImpl;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.net.HostNode;
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
import java.util.Iterator;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * The node for all MySQLServers on one Server.
 *
 * @author  AO Industries, Inc.
 */
public class ServersNode extends NodeImpl {

	private static final long serialVersionUID = 1L;

	final HostNode hostNode;
	private final Server linuxServer;
	private final List<ServerNode> mysqlServerNodes = new ArrayList<>();
	private boolean started;

	public ServersNode(HostNode hostNode, Server linuxServer, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException {
		super(port, csf, ssf);
		this.hostNode = hostNode;
		this.linuxServer = linuxServer;
	}

	@Override
	public HostNode getParent() {
		return hostNode;
	}

	public Server getAOServer() {
		return linuxServer;
	}

	@Override
	public boolean getAllowsChildren() {
		return true;
	}

	@Override
	public List<ServerNode> getChildren() {
		synchronized(mysqlServerNodes) {
			return getSnapshot(mysqlServerNodes);
		}
	}

	/**
	 * The alert level is equal to the highest alert level of its children.
	 */
	@Override
	public AlertLevel getAlertLevel() {
		AlertLevel level;
		synchronized(mysqlServerNodes) {
			level = AlertLevelUtils.getMaxAlertLevel(mysqlServerNodes);
		}
		return constrainAlertLevel(level);
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
		return accessor.getMessage(hostNode.hostsNode.rootNode.locale, "MySQLServersNode.label");
	}

	private final TableListener tableListener = (Table<?> table) -> {
		try {
			verifyMySQLServers();
		} catch(IOException | SQLException err) {
			throw new WrappedException(err);
		}
	};

	public void start() throws IOException, SQLException {
		synchronized(mysqlServerNodes) {
			if(started) throw new IllegalStateException();
			started = true;
			hostNode.hostsNode.rootNode.conn.getMysql().getServer().addTableListener(tableListener, 100);
		}
		verifyMySQLServers();
	}

	public void stop() {
		synchronized(mysqlServerNodes) {
			started = false;
			hostNode.hostsNode.rootNode.conn.getMysql().getServer().removeTableListener(tableListener);
			for(ServerNode mysqlServerNode : mysqlServerNodes) {
				mysqlServerNode.stop();
				hostNode.hostsNode.rootNode.nodeRemoved();
			}
			mysqlServerNodes.clear();
		}
	}

	private void verifyMySQLServers() throws IOException, SQLException {
		assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

		synchronized(mysqlServerNodes) {
			if(!started) return;
		}

		List<com.aoindustries.aoserv.client.mysql.Server> mysqlServers = linuxServer.getMySQLServers();
		synchronized(mysqlServerNodes) {
			if(started) {
				// Remove old ones
				Iterator<ServerNode> mysqlServerNodeIter = mysqlServerNodes.iterator();
				while(mysqlServerNodeIter.hasNext()) {
					ServerNode mysqlServerNode = mysqlServerNodeIter.next();
					com.aoindustries.aoserv.client.mysql.Server mysqlServer = mysqlServerNode.getMySQLServer();
					if(!mysqlServers.contains(mysqlServer)) {
						mysqlServerNode.stop();
						mysqlServerNodeIter.remove();
						hostNode.hostsNode.rootNode.nodeRemoved();
					}
				}
				// Add new ones
				for(int c=0;c<mysqlServers.size();c++) {
					com.aoindustries.aoserv.client.mysql.Server mysqlServer = mysqlServers.get(c);
					if(c>=mysqlServerNodes.size() || !mysqlServer.equals(mysqlServerNodes.get(c).getMySQLServer())) {
						// Insert into proper index
						ServerNode mysqlServerNode = new ServerNode(this, mysqlServer, port, csf, ssf);
						mysqlServerNodes.add(c, mysqlServerNode);
						mysqlServerNode.start();
						hostNode.hostsNode.rootNode.nodeAdded();
					}
				}
			}
		}
	}

	File getPersistenceDirectory() throws IOException {
		File dir = new File(hostNode.getPersistenceDirectory(), "mysql_servers");
		if(!dir.exists()) {
			if(!dir.mkdir()) {
				throw new IOException(
					accessor.getMessage(hostNode.hostsNode.rootNode.locale,
						"error.mkdirFailed",
						dir.getCanonicalPath()
					)
				);
			}
		}
		return dir;
	}
}
