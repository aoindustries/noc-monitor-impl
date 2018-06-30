/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.aoserv.client.HttpdServer;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.HttpdServerResult;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author  AO Industries, Inc.
 */
class HttpdServerNodeWorker extends TableMultiResultNodeWorker<List<Integer>,HttpdServerResult> {

	/**
	 * One unique worker is made per persistence directory (and should match httpdServer exactly)
	 */
	private static final Map<String, HttpdServerNodeWorker> workerCache = new HashMap<>();
	static HttpdServerNodeWorker getWorker(File persistenceDirectory, HttpdServer httpdServer) throws IOException {
		String path = persistenceDirectory.getCanonicalPath();
		synchronized(workerCache) {
			HttpdServerNodeWorker worker = workerCache.get(path);
			if(worker==null) {
				worker = new HttpdServerNodeWorker(persistenceDirectory, httpdServer);
				workerCache.put(path, worker);
			} else {
				if(!worker._httpdServer.equals(httpdServer)) throw new AssertionError("worker.httpdServer!=httpdServer: "+worker._httpdServer+"!="+httpdServer);
			}
			return worker;
		}
	}

	final private HttpdServer _httpdServer;
	private HttpdServer currentHttpdServer;

	private HttpdServerNodeWorker(File persistenceDirectory, HttpdServer httpdServer) throws IOException {
		super(new File(persistenceDirectory, Integer.toString(httpdServer.getPkey())), new HttpdServerResultSerializer());
		this._httpdServer = currentHttpdServer = httpdServer;
	}

	@Override
	protected int getHistorySize() {
		return 2000;
	}

	@Override
	protected List<Integer> getSample() throws Exception {
		// Get the latest limits
		currentHttpdServer = _httpdServer.getTable().getConnector().getHttpdServers().get(_httpdServer.getPkey());
		int concurrency = currentHttpdServer.getConcurrency();
		return Arrays.asList(
			concurrency,
			currentHttpdServer.getMaxConcurrency(),
			currentHttpdServer.getMonitoringConcurrencyLow(),
			currentHttpdServer.getMonitoringConcurrencyMedium(),
			currentHttpdServer.getMonitoringConcurrencyHigh(),
			currentHttpdServer.getMonitoringConcurrencyCritical()
		);
	}

	@Override
	protected AlertLevelAndMessage getAlertLevelAndMessage(List<Integer> sample, Iterable<? extends HttpdServerResult> previousResults) throws Exception {
		int concurrency = sample.get(0);
		int concurrencyCritical = currentHttpdServer.getMonitoringConcurrencyLow();
		if(concurrencyCritical != -1 && concurrency >= concurrencyCritical) {
			return new AlertLevelAndMessage(
				AlertLevel.CRITICAL,
				locale -> accessor.getMessage(
					locale,
					"HttpdServerNodeWorker.alertMessage.critical",
					concurrencyCritical,
					concurrency
				)
			);
		}
		int concurrencyHigh = currentHttpdServer.getMonitoringConcurrencyHigh();
		if(concurrencyHigh != -1 && concurrency >= concurrencyHigh) {
			return new AlertLevelAndMessage(
				AlertLevel.HIGH,
				locale -> accessor.getMessage(
					locale,
					"HttpdServerNodeWorker.alertMessage.high",
					concurrencyHigh,
					concurrency
				)
			);
		}
		int concurrencyMedium = currentHttpdServer.getMonitoringConcurrencyMedium();
		if(concurrencyMedium != -1 && concurrency >= concurrencyMedium) {
			return new AlertLevelAndMessage(
				AlertLevel.MEDIUM,
				locale -> accessor.getMessage(
					locale,
					"HttpdServerNodeWorker.alertMessage.medium",
					concurrencyMedium,
					concurrency
				)
			);
		}
		int concurrencyLow = currentHttpdServer.getMonitoringConcurrencyLow();
		if(concurrencyLow != -1 && concurrency >= concurrencyLow) {
			return new AlertLevelAndMessage(
				AlertLevel.LOW,
				locale -> accessor.getMessage(
					locale,
					"HttpdServerNodeWorker.alertMessage.low",
					concurrencyLow,
					concurrency
				)
			);
		}
		if(concurrencyLow == -1) {
			return new AlertLevelAndMessage(
				AlertLevel.NONE,
				locale -> accessor.getMessage(
					locale,
					"HttpdServerNodeWorker.alertMessage.notAny",
					concurrency
				)
			);
		} else {
			return new AlertLevelAndMessage(
				AlertLevel.NONE,
				locale -> accessor.getMessage(
					locale,
					"HttpdServerNodeWorker.alertMessage.none",
					concurrencyLow,
					concurrency
				)
			);
		}
	}

	@Override
	protected HttpdServerResult newErrorResult(long time, long latency, AlertLevel alertLevel, String error) {
		return new HttpdServerResult(time, latency, alertLevel, error);
	}

	@Override
	protected HttpdServerResult newSampleResult(long time, long latency, AlertLevel alertLevel, List<Integer> sample) {
		return new HttpdServerResult(
			time,
			latency,
			alertLevel,
			sample.get(0),
			sample.get(1),
			sample.get(2),
			sample.get(3),
			sample.get(4),
			sample.get(5)
		);
	}
}
