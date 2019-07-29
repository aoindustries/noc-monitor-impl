/*
 * Copyright 2009, 2016, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor.mysql;

import com.aoindustries.aoserv.client.backup.MysqlReplication;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.noc.monitor.AlertLevelAndMessage;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.TableResultNodeWorker;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.SerializableFunction;
import com.aoindustries.noc.monitor.common.TableResult;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The workers for MySQLDatabaseNode.
 *
 * @author  AO Industries, Inc.
 */
class DatabaseNodeWorker extends TableResultNodeWorker<List<Database.TableStatus>,Object> {

	//private static final Logger logger = Logger.getLogger(MySQLDatabaseNodeWorker.class.getName());

	/**
	 * One unique worker is made per persistence file (and should match the mysqlDatabase exactly)
	 */
	private static final Map<String, DatabaseNodeWorker> workerCache = new HashMap<>();
	static DatabaseNodeWorker getWorker(File persistenceFile, Database mysqlDatabase, MysqlReplication mysqlSlave) throws IOException, SQLException {
		String path = persistenceFile.getCanonicalPath();
		synchronized(workerCache) {
			DatabaseNodeWorker worker = workerCache.get(path);
			if(worker==null) {
				worker = new DatabaseNodeWorker(persistenceFile, mysqlDatabase, mysqlSlave);
				workerCache.put(path, worker);
			} else {
				if(!worker.mysqlDatabase.equals(mysqlDatabase)) throw new AssertionError("worker.mysqlDatabase!=mysqlDatabase: "+worker.mysqlDatabase+"!="+mysqlDatabase);
			}
			return worker;
		}
	}

	// Will use whichever connector first created this worker, even if other accounts connect later.
	final private Database mysqlDatabase;
	final private MysqlReplication mysqlSlave;
	final boolean isSlowServer;
	final private Object lastTableStatusesLock = new Object();
	private List<Database.TableStatus> lastTableStatuses;

	DatabaseNodeWorker(File persistenceFile, Database mysqlDatabase, MysqlReplication mysqlSlave) throws IOException, SQLException {
		super(persistenceFile);
		this.mysqlDatabase = mysqlDatabase;
		this.mysqlSlave = mysqlSlave;
		String hostname = mysqlDatabase.getMySQLServer().getLinuxServer().getHostname().toString();
		this.isSlowServer =
			hostname.equals("www.swimconnection.com")
			// || hostname.equals("www1.leagle.com")
		;
	}

	@Override
	protected int getColumns() {
		return 18;
	}

	@Override
	protected SerializableFunction<Locale,List<String>> getColumnHeaders() {
		return locale -> Arrays.asList(
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.name"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.engine"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.version"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.rowFormat"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.rows"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.avgRowLength"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.dataLength"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.maxDataLength"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.indexLength"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.dataFree"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.autoIncrement"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.createTime"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.updateTime"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.checkTime"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.collation"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.checksum"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.createOptions"),
			accessor.getMessage(locale, "MySQLDatabaseNodeWorker.columnHeader.comment")
		);
	}

	@Override
	protected List<Database.TableStatus> getQueryResult() throws Exception {
		List<Database.TableStatus> tableStatuses = mysqlDatabase.getTableStatus(mysqlSlave);
		setLastTableStatuses(tableStatuses);
		return tableStatuses;
	}

	@Override
	protected SerializableFunction<Locale,List<Object>> getTableData(List<Database.TableStatus> tableStatuses) throws Exception {
		List<Object> tableData = new ArrayList<>(tableStatuses.size()*18);
		for(Database.TableStatus tableStatus : tableStatuses) {
			tableData.add(tableStatus.getName());
			tableData.add(tableStatus.getEngine());
			tableData.add(tableStatus.getVersion());
			tableData.add(tableStatus.getRowFormat());
			tableData.add(tableStatus.getRows());
			tableData.add(tableStatus.getAvgRowLength());
			tableData.add(tableStatus.getDataLength());
			tableData.add(tableStatus.getMaxDataLength());
			tableData.add(tableStatus.getIndexLength());
			tableData.add(tableStatus.getDataFree());
			tableData.add(tableStatus.getAutoIncrement());
			tableData.add(tableStatus.getCreateTime());
			tableData.add(tableStatus.getUpdateTime());
			tableData.add(tableStatus.getCheckTime());
			tableData.add(tableStatus.getCollation());
			tableData.add(tableStatus.getChecksum());
			tableData.add(tableStatus.getCreateOptions());
			tableData.add(tableStatus.getComment());
		}
		return locale -> tableData;
	}

	private void setLastTableStatuses(List<Database.TableStatus> tableStatuses) {
		synchronized(lastTableStatusesLock) {
			this.lastTableStatuses = tableStatuses;
			lastTableStatusesLock.notifyAll();
		}
	}

	/**
	 * Gets the last table statuses.  May wait for the data to become available,
	 * will not return null.  May wait for a very long time in some cases.
	 */
	List<Database.TableStatus> getLastTableStatuses() {
		synchronized(lastTableStatusesLock) {
			while(lastTableStatuses==null) {
				try {
					lastTableStatusesLock.wait();
				} catch(InterruptedException err) {
					// logger.warning("wait interrupted");
				}
			}
			return lastTableStatuses;
		}
	}

	/**
	 * If is a slowServer (many tables), only updates once every 12 hours.
	 * Otherwise updates once every five minutes.
	 */
	@Override
	protected long getSleepDelay(boolean lastSuccessful, AlertLevel alertLevel) {
		if(isSlowServer) return 12L*60*60000; // Only update once every 12 hours
		return 5*60000;
	}

	@Override
	protected long getTimeout() {
		return isSlowServer ? 30 : 5;
	}

	@Override
	protected List<AlertLevel> getAlertLevels(List<Database.TableStatus> tableStatuses) {
		List<AlertLevel> alertLevels = new ArrayList<>(tableStatuses.size());
		for(Database.TableStatus tableStatus : tableStatuses) {
			AlertLevel alertLevel = AlertLevel.NONE;
			// Could compare data length to max data length and warn, but max data length is incredibly high in MySQL 5.0+
			alertLevels.add(alertLevel);
		}
		return alertLevels;
	}

	/**
	 * Determines the alert message for the provided result.
	 */
	@Override
	public AlertLevelAndMessage getAlertLevelAndMessage(AlertLevel curAlertLevel, TableResult result) {
		if(result.isError()) {
			return new AlertLevelAndMessage(
				result.getAlertLevels().get(0),
				locale -> result.getTableData(locale).get(0).toString()
			);
		} else {
			return AlertLevelAndMessage.NONE;
		}
	}
}