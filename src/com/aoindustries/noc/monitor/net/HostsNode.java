/*
 * Copyright 2008-2009, 2014, 2016, 2018, 2019, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.net;

import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.exception.WrappedException;
import com.aoindustries.noc.monitor.AlertLevelUtils;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.NodeImpl;
import com.aoindustries.noc.monitor.RootNodeImpl;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.table.Table;
import com.aoindustries.table.TableListener;
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
 * The top-level node has one child for each of the servers.
 *
 * @author  AO Industries, Inc.
 */
abstract public class HostsNode extends NodeImpl {

	private static final long serialVersionUID = 1L;

	public final RootNodeImpl rootNode;

	private final List<HostNode> hostNodes = new ArrayList<>();
	private boolean started;

	protected HostsNode(RootNodeImpl rootNode, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException {
		super(port, csf, ssf);
		this.rootNode = rootNode;
	}

	@Override
	final public RootNodeImpl getParent() {
		return rootNode;
	}

	@Override
	final public boolean getAllowsChildren() {
		return true;
	}

	@Override
	final public List<HostNode> getChildren() {
		synchronized(hostNodes) {
			return getSnapshot(hostNodes);
		}
	}

	/**
	 * The alert level is equal to the highest alert level of its children.
	 */
	@Override
	final public AlertLevel getAlertLevel() {
		AlertLevel level;
		synchronized(hostNodes) {
			level = AlertLevelUtils.getMaxAlertLevel(hostNodes);
		}
		return constrainAlertLevel(level);
	}

	/**
	 * No alert messages.
	 */
	@Override
	final public String getAlertMessage() {
		return null;
	}

	private final TableListener tableListener = (Table<?> table) -> {
		try {
			verifyServers();
		} catch(IOException | SQLException err) {
			throw new WrappedException(err);
		}
	};

	final public void start() throws IOException, SQLException {
		synchronized(hostNodes) {
			if(started) throw new IllegalStateException();
			started = true;
			rootNode.conn.getNet().getHost().addTableListener(tableListener, 100);
		}
		verifyServers();
	}

	final void stop() {
		synchronized(hostNodes) {
			started = false;
			rootNode.conn.getNet().getHost().removeTableListener(tableListener);
			for(HostNode hostNode : hostNodes) {
				hostNode.stop();
				rootNode.nodeRemoved();
			}
			hostNodes.clear();
		}
	}

	private void verifyServers() throws IOException, SQLException {
		assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

		synchronized(hostNodes) {
			if(!started) return;
		}

		// Get all the servers that have monitoring enabled
		List<Host> allHosts = rootNode.conn.getNet().getHost().getRows();
		List<Host> hosts = new ArrayList<>(allHosts.size());
		for(Host host : allHosts) {
			if(host.isMonitoringEnabled() && includeHost(host)) hosts.add(host);
		}
		synchronized(hostNodes) {
			if(started) {
				// Remove old ones
				Iterator<HostNode> hostNodeIter = hostNodes.iterator();
				while(hostNodeIter.hasNext()) {
					HostNode hostNode = hostNodeIter.next();
					Host host = hostNode.getHost();
					if(!hosts.contains(host)) {
						hostNode.stop();
						hostNodeIter.remove();
						rootNode.nodeRemoved();
					}
				}
				// Add new ones
				for(int c=0;c<hosts.size();c++) {
					Host host = hosts.get(c);
					if(c>=hostNodes.size() || !host.equals(hostNodes.get(c).getHost())) {
						// Insert into proper index
						HostNode hostNode = new HostNode(this, host, port, csf, ssf);
						hostNodes.add(c, hostNode);
						hostNode.start();
						rootNode.nodeAdded();
					}
				}
			}
		}
	}

	/**
	 * Gets the top-level persistence directory.
	 */
	final File getPersistenceDirectory() throws IOException {
		File dir = new File(rootNode.getPersistenceDirectory(), "servers");
		if(!dir.exists()) {
			if(!dir.mkdir()) {
				throw new IOException(
					accessor.getMessage(
						rootNode.locale,
						"error.mkdirFailed",
						dir.getCanonicalPath()
					)
				);
			}
		}
		return dir;
	}

	abstract protected boolean includeHost(Host host) throws SQLException, IOException;
}
