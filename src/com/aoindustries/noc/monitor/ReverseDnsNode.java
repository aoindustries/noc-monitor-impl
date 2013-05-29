/*
 * Copyright 2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.aoserv.client.IPAddress;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

/**
 * The node for the reverse DNS monitoring.
 *
 * @author  AO Industries, Inc.
 */
public class ReverseDnsNode extends TableResultNodeImpl {

    private static final long serialVersionUID = 1L;

    private final IPAddress ipAddress;
    
    ReverseDnsNode(IPAddressNode ipAddressNode) throws IOException, SQLException {
        super(
            ipAddressNode.ipAddressesNode.netDeviceNode._netDevicesNode.serverNode.serversNode.rootNode,
            ipAddressNode,
            ReverseDnsNodeWorker.getWorker(
                ipAddressNode.ipAddressesNode.netDeviceNode._netDevicesNode.serverNode.serversNode.rootNode.monitoringPoint,
                new File(ipAddressNode.getPersistenceDirectory(), "rdns"),
                ipAddressNode.getIPAddress()
            )
        );
        this.ipAddress = ipAddressNode.getIPAddress();
    }

    @Override
    public String getId() {
        return "reverse_dns";
    }

    @Override
    public String getLabel() {
        return accessor.getMessage(/*rootNode.locale,*/ "ReverseDnsNode.label");
    }
}
