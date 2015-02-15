/*
 * Copyright 2008-2009, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.BusinessAdministrator;
import com.aoindustries.aoserv.client.SignupRequest;
import com.aoindustries.dbc.ResultSetHandler;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.TableResult;
import com.aoindustries.noc.monitor.common.TimeWithTimeZone;
import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The workers for 3ware RAID.
 *
 * @author  AO Industries, Inc.
 */
class SignupsNodeWorker extends TableResultNodeWorker<List<Object>,Object> {

    /**
     * One unique worker is made per persistence file.
     */
    private static final Map<String, SignupsNodeWorker> workerCache = new HashMap<String,SignupsNodeWorker>();
    static SignupsNodeWorker getWorker(File persistenceFile, AOServConnector conn) throws IOException {
        String path = persistenceFile.getCanonicalPath();
        synchronized(workerCache) {
            SignupsNodeWorker worker = workerCache.get(path);
            if(worker==null) {
                worker = new SignupsNodeWorker(persistenceFile, conn);
                workerCache.put(path, worker);
            }
            return worker;
        }
    }

    private final AOServConnector conn;

    SignupsNodeWorker(File persistenceFile, AOServConnector conn) {
        super(persistenceFile);
        this.conn = conn;
    }

    /**
     * Determines the alert message for the provided result.
     */
    @Override
    protected AlertLevelAndMessage getAlertLevelAndMessage(Locale locale, TableResult result) {
        List<?> tableData = result.getTableData();
        if(result.isError()) {
            return new AlertLevelAndMessage(result.getAlertLevels().get(0), tableData.get(0).toString());
        } else {
            // Count the number of incompleted signups
            int incompleteCount = 0;
            for(int index=0,len=tableData.size();index<len;index+=6) {
                String completedBy = (String)tableData.get(index+4);
                if(completedBy==null) incompleteCount++;
            }
            if(incompleteCount==0) {
                return new AlertLevelAndMessage(AlertLevel.NONE, "");
            } else {
                return new AlertLevelAndMessage(
                    AlertLevel.CRITICAL,
                    incompleteCount==1
                    ? accessor.getMessage(/*locale,*/ "SignpusNodeWorker.incompleteCount.singular", incompleteCount)
                    : accessor.getMessage(/*locale,*/ "SignpusNodeWorker.incompleteCount.plural", incompleteCount)
                );
            }
        }
    }

    @Override
    protected int getColumns() {
        return 6;
    }

    @Override
    protected List<String> getColumnHeaders(Locale locale) {
        List<String> columnHeaders = new ArrayList<String>(6);
        columnHeaders.add(accessor.getMessage(/*locale,*/ "SignpusNodeWorker.columnHeader.source"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "SignpusNodeWorker.columnHeader.pkey"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "SignpusNodeWorker.columnHeader.time"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "SignpusNodeWorker.columnHeader.ip_address"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "SignpusNodeWorker.columnHeader.completed_by"));
        columnHeaders.add(accessor.getMessage(/*locale,*/ "SignpusNodeWorker.columnHeader.completed_time"));
        return columnHeaders;
    }

    @Override
    protected List<Object> getQueryResult(Locale locale) throws Exception {
        // Add the old signup forms
        final List<Object> tableData = WebSiteDatabase.getDatabase().executeQuery(
            new ResultSetHandler<List<Object>>() {
                @Override
                public List<Object> handleResultSet(ResultSet results) throws SQLException {
					List<Object> tableData = new ArrayList<>();
					while(results.next()) {
						tableData.add("aoweb");
						tableData.add(results.getInt("pkey"));
						tableData.add(new TimeWithTimeZone(results.getTimestamp("time").getTime()));
						tableData.add(results.getString("ip_address"));
						tableData.add(results.getString("completed_by"));
						Timestamp completedTime = results.getTimestamp("completed_time");
	                    tableData.add(completedTime==null ? null : new TimeWithTimeZone(completedTime.getTime()));
					}
					return tableData;
                }
            },
            "select * from signup_requests order by time"
        );

        // Add the aoserv signups
        for(SignupRequest request : conn.getSignupRequests()) {
            tableData.add(request.getPackageDefinition().getBusiness().getAccounting());
            tableData.add(request.getPkey());
            tableData.add(new TimeWithTimeZone(request.getTime().getTime()));
            tableData.add(request.getIpAddress());
            BusinessAdministrator completedBy = request.getCompletedBy();
            tableData.add(completedBy==null ? null : completedBy.getUsername().getUsername());
            Timestamp completedTime = request.getCompletedTime();
            tableData.add(completedTime==null ? null : new TimeWithTimeZone(completedTime.getTime()));
        }
        return tableData;
    }

    @Override
    protected List<Object> getTableData(List<Object> tableData, Locale locale) throws Exception {
        return tableData;
    }

    @Override
    protected List<AlertLevel> getAlertLevels(List<Object> tableData) {
        List<AlertLevel> alertLevels = new ArrayList<AlertLevel>(tableData.size()/6);
        for(int index=0,len=tableData.size();index<len;index+=6) {
            String completedBy = (String)tableData.get(index+4);
            alertLevels.add(completedBy==null ? AlertLevel.CRITICAL : AlertLevel.NONE);
        }
        return alertLevels;
    }
}
