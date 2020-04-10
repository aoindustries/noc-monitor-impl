/*
 * noc-monitor-impl - Implementation of Network Operations Center Monitoring.
 * Copyright (C) 2008, 2009, 2016, 2017, 2018, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of noc-monitor-impl.
 *
 * noc-monitor-impl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * noc-monitor-impl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with noc-monitor-impl.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.noc.monitor.linux;

import com.Ostermiller.util.CSVParse;
import com.Ostermiller.util.CSVParser;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.lang.Strings;
import com.aoindustries.noc.monitor.AlertLevelAndMessage;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.TableResultNodeWorker;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.SerializableFunction;
import com.aoindustries.noc.monitor.common.TableResult;
import com.aoindustries.util.i18n.ThreadLocale;
import java.io.CharArrayReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The workers for filesystem monitoring.
 *
 * @author  AO Industries, Inc.
 */
class FilesystemsNodeWorker extends TableResultNodeWorker<List<String>,String> {

	private static final Logger logger = Logger.getLogger(FilesystemsNodeWorker.class.getName());

	/**
	 * One unique worker is made per persistence file (and should match the linuxServer exactly)
	 */
	private static final Map<String, FilesystemsNodeWorker> workerCache = new HashMap<>();
	static FilesystemsNodeWorker getWorker(File persistenceFile, Server linuxServer) throws IOException {
		String path = persistenceFile.getCanonicalPath();
		synchronized(workerCache) {
			FilesystemsNodeWorker worker = workerCache.get(path);
			if(worker==null) {
				worker = new FilesystemsNodeWorker(persistenceFile, linuxServer);
				workerCache.put(path, worker);
			} else {
				if(!worker.linuxServer.equals(linuxServer)) throw new AssertionError("worker.linuxServer!=linuxServer: "+worker.linuxServer+"!="+linuxServer);
			}
			return worker;
		}
	}

	// Will use whichever connector first created this worker, even if other accounts connect later.
	final private Server linuxServer;

	FilesystemsNodeWorker(File persistenceFile, Server linuxServer) {
		super(persistenceFile);
		this.linuxServer = linuxServer;
	}

	/**
	 * Determines the alert message for the provided result.
	 */
	@Override
	public AlertLevelAndMessage getAlertLevelAndMessage(AlertLevel curAlertLevel, TableResult result) {
		AlertLevel highestAlertLevel = AlertLevel.NONE;
		Function<Locale,String> highestAlertMessage = null;
		if(result.isError()) {
			highestAlertLevel = result.getAlertLevels().get(0);
			highestAlertMessage = locale -> result.getTableData(locale).get(0).toString();
		} else {
			List<?> tableData = result.getTableData(Locale.getDefault());
			for(int index=0,len=tableData.size();index<len;index+=12) {
				AlertLevelAndMessage alam;
				try {
					alam = getAlertLevelAndMessage(tableData, index);
				} catch(Exception err) {
					logger.log(Level.SEVERE, null, err);
					alam = new AlertLevelAndMessage(
						AlertLevel.CRITICAL,
						locale -> ThreadLocale.set(
							locale,
							(Supplier<String>)() -> {
								String msg = err.getLocalizedMessage();
								if(msg == null || msg.isEmpty()) msg = err.toString();
								return msg;
							}
						)
					);
				}
				AlertLevel alertLevel = alam.getAlertLevel();
				if(alertLevel.compareTo(highestAlertLevel)>0) {
					highestAlertLevel = alertLevel;
					highestAlertMessage = alam.getAlertMessage();
				}
			}
		}
		return new AlertLevelAndMessage(highestAlertLevel, highestAlertMessage);
	}

	@Override
	protected int getColumns() {
		return 12;
	}

	@Override
	protected SerializableFunction<Locale,List<String>> getColumnHeaders() {
		return locale -> Arrays.asList(
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.mountpoint"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.device"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.bytes"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.used"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.free"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.use"),
			//accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.inodes"),
			//accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.iused"),
			//accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.ifree"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.iuse"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.fstype"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.mountoptions"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.extstate"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.extmaxmount"),
			accessor.getMessage(locale, "FilesystemsNodeWorker.columnHeader.extchkint")
		);
	}

	@Override
	protected List<String> getQueryResult() throws Exception {
		@SuppressWarnings("deprecation")
		String report = linuxServer.getFilesystemsCsvReport();

		CSVParse csvParser = new CSVParser(new CharArrayReader(report.toCharArray()));
		try {
			// Skip the columns
			String[] line = csvParser.getLine();
			if(line==null) throw new IOException("No lines from report");
			if(line.length!=15) throw new IOException("First line of report doesn't have 15 columns: "+line.length);
			if(
				!"mountpoint".equals(line[0])
				|| !"device".equals(line[1])
				|| !"bytes".equals(line[2])
				|| !"used".equals(line[3])
				|| !"free".equals(line[4])
				|| !"use".equals(line[5])
				|| !"inodes".equals(line[6])
				|| !"iused".equals(line[7])
				|| !"ifree".equals(line[8])
				|| !"iuse".equals(line[9])
				|| !"fstype".equals(line[10])
				|| !"mountoptions".equals(line[11])
				|| !"extstate".equals(line[12])
				|| !"extmaxmount".equals(line[13])
				|| !"extchkint".equals(line[14])
			) throw new IOException("First line is not the expected column labels");

			// Read the report, line-by-line
			List<String> tableData = new ArrayList<>(90); // Most servers don't have more than 6 filesystems
			while((line=csvParser.getLine())!=null) {
				if(line.length!=15) throw new IOException("line.length!=15: "+line.length);
				tableData.add(line[0]); // mountpoint
				tableData.add(line[1]); // device
				tableData.add(Strings.getApproximateSize(Long.parseLong(line[2]))); // bytes
				tableData.add(Strings.getApproximateSize(Long.parseLong(line[3]))); // used
				tableData.add(Strings.getApproximateSize(Long.parseLong(line[4]))); // free
				tableData.add(line[5]); // use
				//tableData.add(line[6]); // inodes
				//tableData.add(line[7]); // iused
				//tableData.add(line[8]); // ifree
				tableData.add(line[9]); // iuse
				tableData.add(line[10]); // fstype
				tableData.add(line[11]); // mountoptions
				tableData.add(line[12]); // extstate
				tableData.add(line[13]); // extmaxmount
				tableData.add(line[14]); // extchkint
			}
			return tableData;
		} finally {
			csvParser.close();
		}
	}

	@Override
	protected SerializableFunction<Locale,List<String>> getTableData(List<String> tableData) throws Exception {
		return locale -> tableData;
	}

	@Override
	protected List<AlertLevel> getAlertLevels(List<String> tableData) {
		List<AlertLevel> alertLevels = new ArrayList<>(tableData.size()/12);
		for(int index=0,len=tableData.size();index<len;index+=12) {
			try {
				AlertLevelAndMessage alam = getAlertLevelAndMessage(tableData, index);
				alertLevels.add(alam.getAlertLevel());
			} catch(Exception err) {
				logger.log(Level.SEVERE, null, err);
				alertLevels.add(AlertLevel.CRITICAL);
			}
		}
		return alertLevels;
	}

	/**
	 * Determines one line of alert level and message.
	 */
	private AlertLevelAndMessage getAlertLevelAndMessage(List<?> tableData, int index) throws Exception {
		AlertLevel highestAlertLevel = AlertLevel.NONE;
		Function<Locale,String> highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.allOk");

		// Check extstate
		String fstype = tableData.get(index+7).toString();
		{
			if(
				"ext2".equals(fstype)
				|| "ext3".equals(fstype)
			) {
				String extstate = tableData.get(index+9).toString();
				if(
					(
						"ext3".equals(fstype)
						&& !"clean".equals(extstate)
					) || (
						"ext2".equals(fstype)
						&& !"not clean".equals(extstate)
						&& !"clean".equals(extstate)
					)
				) {
					AlertLevel newAlertLevel = AlertLevel.CRITICAL;
					if(newAlertLevel.compareTo(highestAlertLevel)>0) {
						highestAlertLevel = newAlertLevel;
						highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.extstate.unexpectedState", extstate);
					}
				}
			}
		}

		// Check for inode percent
		{
			String iuse = tableData.get(index+6).toString();
			if(iuse.length()!=0) {
				if(!iuse.endsWith("%")) throw new IOException("iuse doesn't end with '%': "+iuse);
				int iuseNum = Integer.parseInt(iuse.substring(0, iuse.length()-1));
				final AlertLevel newAlertLevel;
				if(iuseNum<0 || iuseNum>=95) newAlertLevel = AlertLevel.CRITICAL;
				else if(iuseNum>=90) newAlertLevel = AlertLevel.HIGH;
				else if(iuseNum>=85) newAlertLevel = AlertLevel.MEDIUM;
				else if(iuseNum>=80) newAlertLevel = AlertLevel.LOW;
				else newAlertLevel = AlertLevel.NONE;
				if(newAlertLevel.compareTo(highestAlertLevel)>0) {
					highestAlertLevel = newAlertLevel;
					highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.iuse", iuse);
				}
			}
		}

		// Check for disk space percent
		{
			String hostname = linuxServer.getHostname().toString();
			String mountpoint = tableData.get(index).toString();
			String use = tableData.get(index+5).toString();
			if(!use.endsWith("%")) throw new IOException("use doesn't end with '%': "+use);
			int useNum = Integer.parseInt(use.substring(0, use.length()-1));
			final AlertLevel newAlertLevel;
			if(mountpoint.startsWith("/var/backup")) {
				// Backup partitions allow a higher percentage and never go critical
				if(useNum >= 98) newAlertLevel = AlertLevel.HIGH;
				else if(useNum >= 97) newAlertLevel = AlertLevel.MEDIUM;
				else if(useNum >= 96) newAlertLevel = AlertLevel.LOW;
				else newAlertLevel = AlertLevel.NONE;
			} else {
				// Other partitions notify at lower percentages and can go critical
				if(useNum >= 97) newAlertLevel = AlertLevel.CRITICAL;
				else if(useNum >= 94) newAlertLevel = AlertLevel.HIGH;
				else if(useNum >= 91) newAlertLevel = AlertLevel.MEDIUM;
				else if(useNum >= 88) newAlertLevel = AlertLevel.LOW;
				else newAlertLevel = AlertLevel.NONE;
			}
			if(newAlertLevel.compareTo(highestAlertLevel)>0) {
				highestAlertLevel = newAlertLevel;
				highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.use", use);
			}
		}

		// Make sure extmaxmount is -1
		if(highestAlertLevel.compareTo(AlertLevel.LOW)<0) {
			String extmaxmount = tableData.get(index+10).toString();
			switch (fstype) {
				case "ext3":
					if(!"-1".equals(extmaxmount)) {
						highestAlertLevel = AlertLevel.LOW;
						highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.extmaxmount.ext3", extmaxmount);
					}
					break;
				case "ext2":
					if("-1".equals(extmaxmount)) {
						highestAlertLevel = AlertLevel.LOW;
						highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.extmaxmount.ext2", extmaxmount);
					}
					break;
			}
		}

		// Make sure extchkint is 0
		if(highestAlertLevel.compareTo(AlertLevel.LOW)<0) {
			String extchkint = tableData.get(index+11).toString();
			switch (fstype) {
				case "ext3":
					if(!"0 (<none>)".equals(extchkint)) {
						highestAlertLevel = AlertLevel.LOW;
						highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.extchkint.ext3", extchkint);
					}
					break;
				case "ext2":
					if("0 (<none>)".equals(extchkint)) {
						highestAlertLevel = AlertLevel.LOW;
						highestAlertMessage = locale -> accessor.getMessage(locale, "FilesystemsNodeWorker.alertMessage.extchkint.ext2", extchkint);
					}
					break;
			}
		}

		return new AlertLevelAndMessage(highestAlertLevel, highestAlertMessage);
	}
}