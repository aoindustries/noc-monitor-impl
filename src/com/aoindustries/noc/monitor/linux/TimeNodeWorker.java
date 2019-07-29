/*
 * Copyright 2008-2013, 2016, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.linux;

import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.noc.monitor.AlertLevelAndMessage;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.TableMultiResultNodeWorker;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.TimeResult;
import com.aoindustries.sql.MilliInterval;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The clock skew for a single sample in milliseconds is calculated as follows:
 *      st: remote system time (in milliseconds from Epoch)
 *      rt: request time (in milliseconds from Epoch)
 *      l:  request latency (in nanoseconds)
 *
 *      skew = st - (rt + round(l/2000000))
 *
 * Alert levels are:
 *          &gt;=1 minute  Critical
 *          &gt;=4 seconds High
 *          &gt;=2 seconds Medium
 *          &gt;=1 second  Low
 *          &lt;1  second  None
 *
 * @author  AO Industries, Inc.
 */
class TimeNodeWorker extends TableMultiResultNodeWorker<MilliInterval,TimeResult> {

	/**
	 * One unique worker is made per persistence directory (and should match linuxServer exactly)
	 */
	private static final Map<String, TimeNodeWorker> workerCache = new HashMap<>();
	static TimeNodeWorker getWorker(File persistenceDirectory, Server linuxServer) throws IOException {
		String path = persistenceDirectory.getCanonicalPath();
		synchronized(workerCache) {
			TimeNodeWorker worker = workerCache.get(path);
			if(worker==null) {
				worker = new TimeNodeWorker(persistenceDirectory, linuxServer);
				workerCache.put(path, worker);
			} else {
				if(!worker._linuxServer.equals(linuxServer)) throw new AssertionError("worker.linuxServer!=linuxServer: "+worker._linuxServer+"!="+linuxServer);
			}
			return worker;
		}
	}

	final private Server _linuxServer;
	private Server currentLinuxServer;

	private TimeNodeWorker(File persistenceDirectory, Server linuxServer) throws IOException {
		super(new File(persistenceDirectory, "time"), new TimeResultSerializer());
		this._linuxServer = currentLinuxServer = linuxServer;
	}

	@Override
	protected int getHistorySize() {
		return 2000;
	}

	@Override
	protected MilliInterval getSample() throws Exception {
		// Get the latest limits
		currentLinuxServer = _linuxServer.getTable().getConnector().getLinux().getServer().get(_linuxServer.getPkey());

		long requestTime = System.currentTimeMillis();
		long startNanos = System.nanoTime();
		long systemTime = currentLinuxServer.getSystemTimeMillis();
		long latency = System.nanoTime() - startNanos;
		long lRemainder = latency % 2000000;
		long skew = systemTime - (requestTime + latency/2000000);
		if(lRemainder >= 1000000) skew--;

		return new MilliInterval(skew);
	}

	private static AlertLevel getAlertLevel(long skew) {
		if(skew >= 60000 || skew <= -60000) return AlertLevel.CRITICAL;
		if(skew >=  4000 || skew <=  -4000) return AlertLevel.HIGH;
		if(skew >=  2000 || skew <=  -2000) return AlertLevel.MEDIUM;
		if(skew >=  1000 || skew <=  -1000) return AlertLevel.MEDIUM;
		return AlertLevel.NONE;
	}

	@Override
	protected AlertLevelAndMessage getAlertLevelAndMessage(MilliInterval sample, Iterable<? extends TimeResult> previousResults) throws Exception {
		final long currentSkew = sample.getIntervalMillis();

		return new AlertLevelAndMessage(
			getAlertLevel(currentSkew),
			locale -> accessor.getMessage(
				locale,
				"TimeNodeWorker.alertMessage",
				currentSkew
			)
		);
	}

	@Override
	protected TimeResult newErrorResult(long time, long latency, AlertLevel alertLevel, String error) {
		return new TimeResult(time, latency, alertLevel, error);
	}

	@Override
	protected TimeResult newSampleResult(long time, long latency, AlertLevel alertLevel, MilliInterval sample) {
		return new TimeResult(time, latency, alertLevel, sample.getIntervalMillis());
	}
}