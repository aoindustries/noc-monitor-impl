/*
 * Copyright 2008-2009, 2016, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.infrastructure;

import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.TableResultNodeImpl;
import java.io.File;
import java.io.IOException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMIServerSocketFactory;

/**
 * The node for the hard drive temperature monitoring.
 *
 * @author  AO Industries, Inc.
 */
public class HardDrivesTemperatureNode extends TableResultNodeImpl {

	private static final long serialVersionUID = 1L;

	HardDrivesTemperatureNode(HardDrivesNode hardDrivesNode, int port, RMIClientSocketFactory csf, RMIServerSocketFactory ssf) throws IOException {
		super(
			hardDrivesNode.hostNode.hostsNode.rootNode,
			hardDrivesNode,
			HardDrivesTemperatureNodeWorker.getWorker(
				new File(hardDrivesNode.getPersistenceDirectory(), "hddtemp"),
				hardDrivesNode.getLinuxServer()
			),
			port,
			csf,
			ssf
		);
	}

	@Override
	public String getLabel() {
		return accessor.getMessage(rootNode.locale, "HardDrivesTemperatureNode.label");
	}
}