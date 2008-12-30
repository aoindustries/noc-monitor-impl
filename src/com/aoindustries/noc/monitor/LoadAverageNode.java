/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.aoserv.client.AOServer;
import java.io.IOException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The load average per ao_server is watched on a minutely basis.  The five-minute
 * load average is compared against the limits in the ao_servers table and the
 * alert level is set accordingly.
 *
 * @author  AO Industries, Inc.
 */
public class LoadAverageNode extends TableMultiResultNodeImpl {

    private final AOServer _aoServer;

    LoadAverageNode(ServerNode serverNode, AOServer aoServer, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws IOException {
        super(
            serverNode.serversNode.rootNode,
            serverNode,
            LoadAverageNodeWorker.getWorker(
                serverNode.serversNode.rootNode.conn.getErrorHandler(),
                serverNode.getPersistenceDirectory(),
                aoServer
            ),
            port,
            csf,
            ssf
        );
        this._aoServer = aoServer;
    }

    @Override
    public String getLabel() {
        return ApplicationResourcesAccessor.getMessage(rootNode.locale, "LoadAverageNode.label");
    }

    @Override
    public List<?> getColumnHeaders(Locale locale) {
        List<String> headers = new ArrayList<String>(7);
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.oneMinute"));
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.fiveMinute"));
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.tenMinute"));
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.runningProcesses"));
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.totalProcesses"));
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.lastPID"));
        headers.add(ApplicationResourcesAccessor.getMessage(locale, "LoadAverageNode.columnHeader.alertThresholds"));
        return Collections.unmodifiableList(headers);
    }
}
