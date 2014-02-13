/*
 * Copyright 2008-2013, 2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.AOServer.DrbdReport;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.TableResult;
import com.aoindustries.noc.monitor.common.TimeWithTimeZone;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * The workers for DRBD.
 *
 * @author  AO Industries, Inc.
 */
class DrbdNodeWorker extends TableResultNodeWorker<List<DrbdReport>,Object> {

	private static final int NUM_COLS = 7;

	private static final int
		LOW_DAYS = 15,
		MEDIUM_DAYS = 21,
		HIGH_DAYS = 28
	;

	/**
     * One unique worker is made per persistence file (and should match the aoServer exactly)
     */
    private static final Map<String, DrbdNodeWorker> workerCache = new HashMap<>();
    static DrbdNodeWorker getWorker(File persistenceFile, AOServer aoServer) throws IOException, SQLException {
        String path = persistenceFile.getCanonicalPath();
        synchronized(workerCache) {
            DrbdNodeWorker worker = workerCache.get(path);
            if(worker==null) {
                worker = new DrbdNodeWorker(persistenceFile, aoServer);
                workerCache.put(path, worker);
            } else {
                if(!worker.aoServer.equals(aoServer)) throw new AssertionError("worker.aoServer!=aoServer: "+worker.aoServer+"!="+aoServer);
            }
            return worker;
        }
    }

    // Will use whichever connector first created this worker, even if other accounts connect later.
    final private AOServer aoServer;
	final private TimeZone timeZone;

    DrbdNodeWorker(File persistenceFile, AOServer aoServer) throws IOException, SQLException {
        super(persistenceFile);
        this.aoServer = aoServer;
		this.timeZone = aoServer.getTimeZone().getTimeZone();
    }

    /**
     * Determines the alert message for the provided result.
     * 
     * @link http://www.drbd.org/users-guide/ch-admin.html#s-disk-states
     */
    @Override
    protected AlertLevelAndMessage getAlertLevelAndMessage(Locale locale, TableResult result) {
        AlertLevel highestAlertLevel = AlertLevel.NONE;
        String highestAlertMessage = "";
        List<?> tableData = result.getTableData();
        if(result.isError()) {
            highestAlertLevel = result.getAlertLevels().get(0);
            highestAlertMessage = tableData.get(0).toString();
        } else {
            List<AlertLevel> alertLevels = result.getAlertLevels();
            for(
				int index=0, len=tableData.size();
				index<len;
				index += NUM_COLS
			) {
                AlertLevel alertLevel = alertLevels.get(index / NUM_COLS);
                if(alertLevel.compareTo(highestAlertLevel)>0) {
                    highestAlertLevel = alertLevel;
					String device = (String)tableData.get(index);
					String resource = (String)tableData.get(index + 1);
					String cstate = (String)tableData.get(index + 2);
					String dstate = (String)tableData.get(index + 3);
					String roles = (String)tableData.get(index + 4);
					TimeWithTimeZone lastVerified = (TimeWithTimeZone)tableData.get(index + 5);
					Long outOfSync = (Long)tableData.get(index + 6);
                    highestAlertMessage = device+" "+resource+" "+cstate+" "+dstate+" "+roles+" "+lastVerified+" "+outOfSync;
                }
            }
        }
        return new AlertLevelAndMessage(highestAlertLevel, highestAlertMessage);
    }

    @Override
    protected int getColumns() {
        return NUM_COLS;
    }

    @Override
    protected List<String> getColumnHeaders(Locale locale) {
        List<String> columnHeaders = new ArrayList<>(NUM_COLS);
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.device"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.resource"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.cs"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.ds"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.roles"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.lastVerified"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "DrbdNodeWorker.columnHeader.outOfSync"));
        return columnHeaders;
    }

    @Override
    protected List<DrbdReport> getQueryResult(Locale locale) throws Exception {
        return aoServer.getDrbdReport();
    }

    @Override
    protected List<Object> getTableData(List<DrbdReport> reports, Locale locale) throws Exception {
        List<Object> tableData = new ArrayList<>(reports.size() * NUM_COLS);
        for(DrbdReport report : reports) {
            tableData.add(report.getDevice());
            tableData.add(report.getResourceHostname()+'-'+report.getResourceDevice());
            tableData.add(report.getConnectionState().toString());
            tableData.add(report.getLocalDiskState()+"/"+report.getRemoteDiskState());
            tableData.add(report.getLocalRole()+"/"+report.getRemoteRole());
			Long lastVerified = report.getLastVerified();
            tableData.add(lastVerified==null ? null : new TimeWithTimeZone(lastVerified, timeZone));
			tableData.add(report.getOutOfSync());
        }
        return tableData;
    }

    @Override
    protected List<AlertLevel> getAlertLevels(List<DrbdReport> reports) {
		final long currentTime = System.currentTimeMillis();
        List<AlertLevel> alertLevels = new ArrayList<>(reports.size());
        for(DrbdReport report : reports) {
			final AlertLevel alertLevel;
			// High alert if any out of sync
			if(report.getOutOfSync() != 0) {
				alertLevel = AlertLevel.HIGH;
			} else {
				DrbdReport.ConnectionState connectionState = report.getConnectionState();
				if(
					(
						connectionState!=DrbdReport.ConnectionState.Connected
						&& connectionState!=DrbdReport.ConnectionState.VerifyS
						&& connectionState!=DrbdReport.ConnectionState.VerifyT
					) || report.getLocalDiskState()!=DrbdReport.DiskState.UpToDate
					|| report.getRemoteDiskState()!=DrbdReport.DiskState.UpToDate
					|| !(
						(report.getLocalRole()==DrbdReport.Role.Primary && report.getRemoteRole()==DrbdReport.Role.Secondary)
						|| (report.getLocalRole()==DrbdReport.Role.Secondary && report.getRemoteRole()==DrbdReport.Role.Primary)
					)
				) {
					alertLevel = AlertLevel.HIGH;
				} else {
					// Only check the verified time when primary on at last one side
					if(
						report.getLocalRole()==DrbdReport.Role.Primary
						|| report.getRemoteRole()==DrbdReport.Role.Primary
					) {
						// Check the time since last verified
						Long lastVerified = report.getLastVerified();
						if(lastVerified == null) {
							// Never verified
							alertLevel = AlertLevel.HIGH;
						} else {
							long daysSince = TimeUnit.DAYS.convert(
								Math.abs(currentTime - lastVerified),
								TimeUnit.MILLISECONDS
							);
							if     (daysSince >= HIGH_DAYS)   alertLevel = AlertLevel.HIGH;
							else if(daysSince >= MEDIUM_DAYS) alertLevel = AlertLevel.MEDIUM;
							else if(daysSince >= LOW_DAYS)    alertLevel = AlertLevel.LOW;
							else                              alertLevel = AlertLevel.NONE;
						}
					} else {
						// Secondary/Secondary not verified, no alerts
						alertLevel = AlertLevel.NONE;
					}
				}
			}
            alertLevels.add(alertLevel);
        }
        return alertLevels;
    }
}
