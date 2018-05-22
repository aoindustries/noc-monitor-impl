/*
 * Copyright 2008-2009, 2014, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.TableResult;
import com.aoindustries.noc.monitor.common.TableResultListener;
import com.aoindustries.noc.monitor.common.TableResultNode;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * The node for table results.
 *
 * @author  AO Industries, Inc.
 */
abstract public class TableResultNodeImpl extends NodeImpl implements TableResultNode {

	private static final Logger logger = Logger.getLogger(TableResultNodeImpl.class.getName());

	private static final long serialVersionUID = 1L;

	final RootNodeImpl rootNode;
	final NodeImpl parent;
	final TableResultNodeWorker<?,?> worker;

	final private List<TableResultListener> tableResultListeners = new ArrayList<>();

	TableResultNodeImpl(RootNodeImpl rootNode, NodeImpl parent, TableResultNodeWorker<?,?> worker, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException {
		super(port, csf, ssf);
		this.rootNode = rootNode;
		this.parent = parent;
		this.worker = worker;
	}

	@Override
	final public NodeImpl getParent() {
		return parent;
	}

	@Override
	public boolean getAllowsChildren() {
		return false;
	}

	@Override
	public List<? extends NodeImpl> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public AlertLevel getAlertLevel() {
		AlertLevel alertLevel = worker.getAlertLevel();
		return constrainAlertLevel(alertLevel == null ? AlertLevel.UNKNOWN : alertLevel);
	}

	@Override
	final public String getAlertMessage() {
		Function<Locale,String> alertMessage = worker.getAlertMessage();
		return alertMessage == null ? null : alertMessage.apply(rootNode.locale);
	}

	void start() throws IOException {
		worker.addTableResultNodeImpl(this);
	}

	void stop() {
		worker.removeTableResultNodeImpl(this);
	}

	@Override
	final public TableResult getLastResult() {
		return worker.getLastResult();
	}

	/**
	 * Called by the worker when the alert level changes.
	 */
	final void nodeAlertLevelChanged(AlertLevel oldAlertLevel, AlertLevel newAlertLevel, Function<Locale,String> alertMessage) throws RemoteException {
		assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

		rootNode.nodeAlertLevelChanged(
			this,
			constrainAlertLevel(oldAlertLevel),
			constrainAlertLevel(newAlertLevel),
			alertMessage == null ? null : alertMessage.apply(rootNode.locale)
		);
	}

	@Override
	final public void addTableResultListener(TableResultListener tableResultListener) {
		synchronized(tableResultListeners) {
			tableResultListeners.add(tableResultListener);
		}
	}

	@Override
	final public void removeTableResultListener(TableResultListener tableResultListener) {
		synchronized(tableResultListeners) {
			for(int c=tableResultListeners.size()-1;c>=0;c--) {
				if(tableResultListeners.get(c).equals(tableResultListener)) {
					tableResultListeners.remove(c);
					// Remove only once, in case add and remove come in out of order with quick GUI changes
					return;
				}
			}
		}
		logger.log(Level.WARNING, null, new AssertionError("Listener not found: " + tableResultListener));
	}

	/**
	 * Notifies all of the listeners.
	 */
	final void tableResultUpdated(TableResult tableResult) {
		assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

		synchronized(tableResultListeners) {
			Iterator<TableResultListener> I = tableResultListeners.iterator();
			while(I.hasNext()) {
				TableResultListener tableResultListener = I.next();
				try {
					tableResultListener.tableResultUpdated(tableResult);
				} catch(RemoteException err) {
					I.remove();
					logger.log(Level.SEVERE, null, err);
				}
			}
		}
	}
}
