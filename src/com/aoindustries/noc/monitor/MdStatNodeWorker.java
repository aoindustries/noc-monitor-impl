/*
 * Copyright 2008-2013, 2014, 2016, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.lang.EnumUtils;
import static com.aoindustries.noc.monitor.ApplicationResources.accessor;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.SingleResult;
import com.aoindustries.util.StringUtility;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The workers for watching /proc/mdstat.
 *
 * @author  AO Industries, Inc.
 */
class MdStatNodeWorker extends SingleResultNodeWorker {

	private enum RaidLevel {
		LINEAR,
		RAID1,
		RAID5,
		RAID6
	}

	/**
	 * One unique worker is made per persistence file (and should match the aoServer exactly)
	 */
	private static final Map<String, MdStatNodeWorker> workerCache = new HashMap<>();
	static MdStatNodeWorker getWorker(File persistenceFile, AOServer aoServer) throws IOException {
		String path = persistenceFile.getCanonicalPath();
		synchronized(workerCache) {
			MdStatNodeWorker worker = workerCache.get(path);
			if(worker==null) {
				worker = new MdStatNodeWorker(persistenceFile, aoServer);
				workerCache.put(path, worker);
			} else {
				if(!worker.aoServer.equals(aoServer)) throw new AssertionError("worker.aoServer!=aoServer: "+worker.aoServer+"!="+aoServer);
			}
			return worker;
		}
	}

	// Will use whichever connector first created this worker, even if other accounts connect later.
	final private AOServer aoServer;

	MdStatNodeWorker(File persistenceFile, AOServer aoServer) {
		super(persistenceFile);
		this.aoServer = aoServer;
	}

	@Override
	protected String getReport() throws IOException, SQLException {
		return aoServer.getMdStatReport();
	}

	/**
	 * Determines the alert level and message for the provided result.
	 *
	 * raid1:
	 *      one up + zero down: medium
	 *      zero down: none
	 *      three+ up: low
	 *      two up...: medium
	 *      one up...: high
	 *      zero up..: critical
	 * raid5:
	 *      one down.: high
	 *      two+ down: critical
	 * raid6:
	 *      one down.: medium
	 *      two down.: high
	 *      two+ down: critical
	 */
	@Override
	protected AlertLevelAndMessage getAlertLevelAndMessage(AlertLevel curAlertLevel, SingleResult result) {
		Function<Locale,String> error = result.getError();
		if(error != null) {
			return new AlertLevelAndMessage(
				// Don't downgrade UNKNOWN to CRITICAL on error
				EnumUtils.max(AlertLevel.CRITICAL, curAlertLevel),
				locale -> accessor.getMessage(
					locale,
					"MdStatNode.alertMessage.error",
					error.apply(locale)
				)
			);
		}
		String report = result.getReport();
		List<String> lines = StringUtility.splitLines(report);
		RaidLevel lastRaidLevel = null;
		AlertLevel highestAlertLevel = AlertLevel.NONE;
		Function<Locale,String> highestAlertMessage = null;
		for(String line : lines) {
			if(
				!line.startsWith("Personalities :")
				&& !line.startsWith("unused devices:")
				&& !(
					line.startsWith("      bitmap: ")
					&& line.endsWith(" chunk")
				)
				// Skip routine RAID check progress line:
				&& !(
					line.startsWith("      [")
					&& line.contains("]  check = ")
				)
			) {
				if(line.indexOf(':')!=-1) {
					// Must contain raid type
					if(line.contains(" linear ")) lastRaidLevel = RaidLevel.LINEAR;
					else if(line.contains(" raid1 ")) lastRaidLevel = RaidLevel.RAID1;
					else if(line.contains(" raid5 ")) lastRaidLevel = RaidLevel.RAID5;
					else if(line.contains(" raid6 ")) lastRaidLevel = RaidLevel.RAID6;
					else {
						return new AlertLevelAndMessage(
							AlertLevel.CRITICAL,
							locale -> accessor.getMessage(
								locale,
								"MdStatNode.alertMessage.noRaidType",
								line
							)
						);
					}
				} else {
					// Resync is low priority
					if(
						(
							line.contains("resync")
							|| line.contains("recovery")
						)
						&& line.contains("finish")
						&& line.contains("speed")
					) {
						if(AlertLevel.LOW.compareTo(highestAlertLevel)>0) {
							highestAlertLevel = AlertLevel.LOW;
							highestAlertMessage = locale -> accessor.getMessage(
								locale,
								"MdStatNode.alertMessage.resync",
								line.trim()
							);
						}
					} else {
						int pos1 = line.indexOf('[');
						if(pos1!=-1) {
							int pos2=line.indexOf(']', pos1+1);
							if(pos2!=-1) {
								pos1 = line.indexOf('[', pos2+1);
								if(pos1!=-1) {
									pos2=line.indexOf(']', pos1+1);
									if(pos2!=-1) {
										// Count the down and up between the brackets
										final int upCount;
										final int downCount;
										{
											int up = 0;
											int down = 0;
											for(int pos=pos1+1;pos<pos2;pos++) {
												char ch = line.charAt(pos);
												if(ch=='U') up++;
												else if(ch=='_') down++;
												else {
													return new AlertLevelAndMessage(
														AlertLevel.CRITICAL,
														locale -> accessor.getMessage(
															locale,
															"MdStatNode.alertMessage.invalidCharacter",
															ch
														)
													);
												}
											}
											upCount = up;
											downCount = down;
										}
										// Get the current alert level
										final AlertLevel alertLevel;
										final Function<Locale,String> alertMessage;
										if(lastRaidLevel==RaidLevel.RAID1) {
											if(upCount==1 && downCount==0) {
												// xen917-4.fc.aoindustries.com has a bad drive we don't fix, this is normal for it
												/*if(aoServer.getHostname().toString().equals("xen917-4.fc.aoindustries.com")) alertLevel = AlertLevel.NONE;
												else*/ alertLevel = AlertLevel.MEDIUM;
											}
											else if(downCount==0) alertLevel = AlertLevel.NONE;
											else if(upCount>=3) alertLevel = AlertLevel.LOW;
											else if(upCount==2) alertLevel = AlertLevel.MEDIUM;
											else if(upCount==1) alertLevel = AlertLevel.HIGH;
											else if(upCount==0) alertLevel = AlertLevel.CRITICAL;
											else throw new AssertionError("upCount should have already matched");
											alertMessage = locale -> accessor.getMessage(
												locale,
												"MdStatNode.alertMessage.raid1",
												upCount,
												downCount
											);
										} else if(lastRaidLevel==RaidLevel.RAID5) {
											if(downCount==0) alertLevel = AlertLevel.NONE;
											else if(downCount==1) alertLevel = AlertLevel.HIGH;
											else if(downCount>=2) alertLevel = AlertLevel.CRITICAL;
											else throw new AssertionError("downCount should have already matched");
											alertMessage = locale -> accessor.getMessage(
												locale,
												"MdStatNode.alertMessage.raid5",
												upCount,
												downCount
											);
										} else if(lastRaidLevel==RaidLevel.RAID6) {
											if(downCount==0) alertLevel = AlertLevel.NONE;
											else if(downCount==1) alertLevel = AlertLevel.MEDIUM;
											else if(downCount==2) alertLevel = AlertLevel.HIGH;
											else if(downCount>=3) alertLevel = AlertLevel.CRITICAL;
											else throw new AssertionError("downCount should have already matched");
											alertMessage = locale -> accessor.getMessage(
												locale,
												"MdStatNode.alertMessage.raid6",
												upCount,
												downCount
											);
										} else {
											final RaidLevel raidLevel = lastRaidLevel;
											return new AlertLevelAndMessage(
												AlertLevel.CRITICAL,
												locale -> accessor.getMessage(
													locale,
													"MdStatNode.alertMessage.unexpectedRaidLevel",
													raidLevel
												)
											);
										}
										if(alertLevel.compareTo(highestAlertLevel)>0) {
											highestAlertLevel = alertLevel;
											highestAlertMessage = alertMessage;
										}
									} else {
										return new AlertLevelAndMessage(
											AlertLevel.CRITICAL,
											locale -> accessor.getMessage(
												locale,
												"MdStatNode.alertMessage.unableToFindCharacter",
												']',
												line
											)
										);
									}
								} else {
									return new AlertLevelAndMessage(
										AlertLevel.CRITICAL,
										locale -> accessor.getMessage(
											locale,
											"MdStatNode.alertMessage.unableToFindCharacter",
											'[',
											line
										)
									);
								}
							} else {
								return new AlertLevelAndMessage(
									AlertLevel.CRITICAL,
									locale -> accessor.getMessage(
										locale,
										"MdStatNode.alertMessage.unableToFindCharacter",
										']',
										line
									)
								);
							}
						}
					}
				}
			}
		}
		return new AlertLevelAndMessage(highestAlertLevel, highestAlertMessage);
	}
}
