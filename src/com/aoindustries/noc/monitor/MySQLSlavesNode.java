/*
 * Copyright 2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.aoserv.client.FailoverMySQLReplication;
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
import java.util.Iterator;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * The node for all FailoverMySQLReplications on one MySQLServer.
 *
 * @author  AO Industries, Inc.
 */
public class MySQLSlavesNode extends NodeImpl {

    private static final long serialVersionUID = 1L;

    final MySQLServerNode mysqlServerNode;
    private final List<MySQLSlaveNode> mysqlSlaveNodes = new ArrayList<MySQLSlaveNode>();

    MySQLSlavesNode(MySQLServerNode mysqlServerNode, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws RemoteException {
        super(port, csf, ssf);
        this.mysqlServerNode = mysqlServerNode;
    }

    @Override
    public Node getParent() {
        return mysqlServerNode;
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
        synchronized(mysqlSlaveNodes) {
            return Collections.unmodifiableList(new ArrayList<MySQLSlaveNode>(mysqlSlaveNodes));
        }
    }

    /**
     * The alert level is equal to the highest alert level of its children.
     */
    @Override
    public AlertLevel getAlertLevel() {
        synchronized(mysqlSlaveNodes) {
            AlertLevel level = AlertLevel.NONE;
            for(NodeImpl mysqlSlaveNode : mysqlSlaveNodes) {
                AlertLevel mysqlSlaveNodeLevel = mysqlSlaveNode.getAlertLevel();
                if(mysqlSlaveNodeLevel.compareTo(level)>0) level = mysqlSlaveNodeLevel;
            }
            return level;
        }
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
        return ApplicationResourcesAccessor.getMessage(mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.locale, "MySQLSlavesNode.label");
    }
    
    private TableListener tableListener = new TableListener() {
        @Override
        public void tableUpdated(Table table) {
            try {
                verifyMySQLSlaves();
            } catch(IOException err) {
                throw new WrappedException(err);
            } catch(SQLException err) {
                throw new WrappedException(err);
            }
        }
    };

    void start() throws IOException, SQLException {
        synchronized(mysqlSlaveNodes) {
            mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.conn.getFailoverFileReplications().addTableListener(tableListener, 100);
            mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.conn.getFailoverMySQLReplications().addTableListener(tableListener, 100);
            verifyMySQLSlaves();
        }
    }
    
    void stop() {
        synchronized(mysqlSlaveNodes) {
            mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.conn.getFailoverFileReplications().removeTableListener(tableListener);
            mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.conn.getFailoverMySQLReplications().removeTableListener(tableListener);
            for(MySQLSlaveNode mysqlSlaveNode : mysqlSlaveNodes) {
                mysqlSlaveNode.stop();
                mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.nodeRemoved();
            }
            mysqlSlaveNodes.clear();
        }
    }

    private void verifyMySQLSlaves() throws IOException, SQLException {
        assert !SwingUtilities.isEventDispatchThread() : "Running in Swing event dispatch thread";

        List<FailoverMySQLReplication> mysqlReplications = mysqlServerNode.getMySQLServer().getFailoverMySQLReplications();
        synchronized(mysqlSlaveNodes) {
            // Remove old ones
            Iterator<MySQLSlaveNode> mysqlSlaveNodeIter = mysqlSlaveNodes.iterator();
            while(mysqlSlaveNodeIter.hasNext()) {
                MySQLSlaveNode mysqlSlaveNode = mysqlSlaveNodeIter.next();
                FailoverMySQLReplication mysqlReplication = mysqlSlaveNode.getFailoverMySQLReplication();
                if(!mysqlReplications.contains(mysqlReplication)) {
                    mysqlSlaveNode.stop();
                    mysqlSlaveNodeIter.remove();
                    mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.nodeRemoved();
                }
            }
            // Add new ones
            for(int c=0;c<mysqlReplications.size();c++) {
                FailoverMySQLReplication mysqlReplication = mysqlReplications.get(c);
                if(c>=mysqlSlaveNodes.size() || !mysqlReplication.equals(mysqlSlaveNodes.get(c).getFailoverMySQLReplication())) {
                    // Insert into proper index
                    MySQLSlaveNode mysqlSlaveNode = new MySQLSlaveNode(this, mysqlReplication, port, csf, ssf);
                    mysqlSlaveNodes.add(c, mysqlSlaveNode);
                    mysqlSlaveNode.start();
                    mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.nodeAdded();
                }
            }
        }
    }

    File getPersistenceDirectory() throws IOException {
        File dir = new File(mysqlServerNode.getPersistenceDirectory(), "mysql_slaves");
        if(!dir.exists()) {
            if(!dir.mkdir()) {
                throw new IOException(
                    ApplicationResourcesAccessor.getMessage(
                        mysqlServerNode._mysqlServersNode.serverNode.serversNode.rootNode.locale,
                        "error.mkdirFailed",
                        dir.getCanonicalPath()
                    )
                );
            }
        }
        return dir;
    }
}