/*
 * noc-monitor-impl - Implementation of Network Operations Center Monitoring.
 * Copyright (C) 2009-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.noc.monitor.email;

import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.net.monitoring.IpAddressMonitoring;
import com.aoindustries.collections.AoCollections;
import com.aoindustries.lang.Throwables;
import com.aoindustries.net.DomainName;
import com.aoindustries.noc.monitor.AlertLevelAndMessage;
import static com.aoindustries.noc.monitor.Resources.PACKAGE_RESOURCES;
import com.aoindustries.noc.monitor.RootNodeImpl;
import com.aoindustries.noc.monitor.TableResultNodeWorker;
import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.TableResult;
import com.aoindustries.noc.monitor.common.TimeWithTimeZone;
import com.aoindustries.sql.NanoInterval;
import com.aoindustries.util.function.SerializableFunction;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Type;

/**
 * The workers for blacklist monitoring.
 *
 * TODO: There are several whitelists tested at multirbl.valli.org
 * TODO: There are also informational lists at multirbl.valli.org
 * TODO: Also at multirbl.valli.org there are hostname-based blacklists...
 * TODO: yahoo, hotmail, gmail, aol?
 * TODO: How to check when rejected by domain name on sender address like done for NMW on att domains?
 *
 * TODO: Possibly more at http://stopspam.org/rblcheck/index.php
 *
 * @author  AO Industries, Inc.
 */
class BlacklistsNodeWorker extends TableResultNodeWorker<List<BlacklistsNodeWorker.BlacklistQueryResult>, Object> {

	private static final Logger logger = Logger.getLogger(BlacklistsNodeWorker.class.getName());

	/**
	 * The results timeout in milliseconds, allows for time in the queue waiting for resolver.
	 */
	private static final long TIMEOUT = 60L*1000L; // Was 15 minutes

	/**
	 * The resolver timeout in milliseconds.
	 */
	private static final Duration RESOLVER_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * The number of milliseconds to wait before trying a timed-out lookup.
	 * This does not apply to a queue time-out, only an actual lookup timeout.
	 */
	private static final long UNKNOWN_RETRY = 4L*60L*60L*1000L; // Was 15 minutes

	/**
	 * The number of milliseconds to wait before looking up a previously good result.
	 */
	private static final long GOOD_RETRY = 24L*60L*60L*1000L;

	/**
	 * The number of milliseconds to wait before looking up a previously bad result.
	 */
	private static final long BAD_RETRY = 60L*60L*1000L;

	/**
	 * The maximum number of threads.
	 */
	private static final int NUM_THREADS = 32; // Was 8; // Was 16; // Was 32;

	/**
	 * The delay between submitting each task in milliseconds.
	 */
	private static final int TASK_DELAY = 100;

	static class BlacklistQueryResult {

		final String basename;
		final long queryTime;
		final long latency;
		final String query;
		final String result;
		final AlertLevel alertLevel;

		BlacklistQueryResult(String basename, long queryTime, long latency, String query, String result, AlertLevel alertLevel) {
			this.basename = basename;
			this.queryTime = queryTime;
			this.latency = latency;
			this.query = query;
			this.result = result;
			this.alertLevel = alertLevel;
		}
	}

	/**
	 * This cache is used for all DNS lookups.
	 */
	static {
		Cache inCache = Lookup.getDefaultCache(DClass.IN);
		inCache.setMaxEntries(-1);
		inCache.setMaxCache(300);
		inCache.setMaxNCache(300);
		Resolver resolver = Lookup.getDefaultResolver();
		resolver.setTimeout(RESOLVER_TIMEOUT);
		if(logger.isLoggable(Level.FINE)) {
			logger.log(Level.FINE, "maxCache={0}", inCache.getMaxCache());
			logger.log(Level.FINE, "maxEntries={0}", inCache.getMaxEntries());
			logger.log(Level.FINE, "maxNCache={0}", inCache.getMaxNCache());
		}
	}

	abstract class BlacklistLookup implements Comparable<BlacklistLookup>, Callable<BlacklistQueryResult> {

		@Override
		final public int compareTo(BlacklistLookup o) {
			return DomainName.compareLabels(getBaseName(), o.getBaseName());
		}

		abstract String getBaseName();
		abstract String getQuery();
		abstract AlertLevel getMaxAlertLevel();
	}

	class DnsBlacklist extends BlacklistLookup {

		final String basename;
		final AlertLevel maxAlertLevel;
		final String query;

		DnsBlacklist(String basename) {
			this(basename, AlertLevel.LOW);
		}

		@SuppressWarnings("deprecation")
		DnsBlacklist(String basename, AlertLevel maxAlertLevel) {
			this.basename = basename;
			this.maxAlertLevel = maxAlertLevel;
			com.aoindustries.net.InetAddress ip = ipAddress.getExternalInetAddress();
			if(ip==null) ip = ipAddress.getInetAddress();
			com.aoindustries.net.AddressFamily addressFamily = ip.getAddressFamily();
			if(addressFamily != com.aoindustries.net.AddressFamily.INET) throw new UnsupportedOperationException("Address family not yet implemented: " + addressFamily);
			int bits = IpAddress.getIntForIPAddress(ip.toString());
			this.query =
				new StringBuilder(16+basename.length())
				.append(bits&255)
				.append('.')
				.append((bits>>>8)&255)
				.append('.')
				.append((bits>>>16)&255)
				.append('.')
				.append((bits>>>24)&255)
				.append('.')
				.append(basename)
				.append('.')
				.toString()
			;
		}

		@Override
		public BlacklistQueryResult call() throws Exception {
			long startTime = System.currentTimeMillis();
			long startNanos = System.nanoTime();
			String result;
			AlertLevel alertLevel;
			// Lookup the IP addresses
			Lookup aLookup = new Lookup(query, Type.A);
			aLookup.run();
			if(aLookup.getResult()==Lookup.HOST_NOT_FOUND) {
				// Not blacklisted
				result = "Host not found";
				alertLevel = AlertLevel.NONE;
			} else if(aLookup.getResult()==Lookup.TYPE_NOT_FOUND) {
				// Not blacklisted
				result = "Type not found";
				alertLevel = AlertLevel.NONE;
			} else if(aLookup.getResult()!=Lookup.SUCCESSFUL) {
				String errorString = aLookup.getErrorString();
				switch (errorString) {
					case "SERVFAIL":
						// Not blacklisted
						result = "SERVFAIL";
						alertLevel = AlertLevel.NONE;
						break;
					case "timed out":
						result = "Timeout";
						alertLevel = AlertLevel.NONE; // Was UNKNOWN
						break;
					default:
						result = "A lookup failed: "+errorString;
						alertLevel = maxAlertLevel;
						break;
				}
			} else {
				Record[] aRecords = aLookup.getAnswers();
				// Pick a random A
				if(aRecords.length==0) {
					result = "No A records found";
					alertLevel = maxAlertLevel;
				} else {
					ARecord a;
					if(aRecords.length==1) a = (ARecord)aRecords[0];
					else a = (ARecord)aRecords[RootNodeImpl.random.nextInt(aRecords.length)];
					String ip = a.getAddress().getHostAddress();
					// Try TXT record
					Lookup txtLookup = new Lookup(query, Type.TXT);
					txtLookup.run();
					if(txtLookup.getResult()==Lookup.SUCCESSFUL) {
						Record[] answers = txtLookup.getAnswers();
						if(answers.length>0) {
							StringBuilder SB = new StringBuilder(ip);
							for(Record record : answers) {
								SB.append(" - ").append(record.rdataToString());
							}
							result = SB.toString();
						} else {
							result = ip.intern();
						}
					} else {
						result = ip.intern();
					}
					alertLevel =
						// list.quorum.to returns 127.0.0.0 for no listing
						("list.quorum.to".equals(basename) && "127.0.0.0".equals(ip)) ? AlertLevel.NONE
						: maxAlertLevel
					;
				}
			}
			return new BlacklistQueryResult(basename, startTime, System.nanoTime() - startNanos, query, result, alertLevel);
		}

		@Override
		String getBaseName() {
			return basename;
		}

		@Override
		AlertLevel getMaxAlertLevel() {
			return maxAlertLevel;
		}

		@Override
		String getQuery() {
			return query;
		}
	}

	class SmtpBlacklist extends BlacklistLookup {

		private final String domain;
		private final AlertLevel maxAlertLevel;
		private final String unknownQuery;

		SmtpBlacklist(String domain) {
			this(domain, AlertLevel.LOW);
		}

		SmtpBlacklist(String domain, AlertLevel maxAlertLevel) {
			this.domain = domain;
			this.maxAlertLevel = maxAlertLevel;
			unknownQuery = "N/A";
		}

		@Override
		String getBaseName() {
			return domain;
		}

		@Override
		AlertLevel getMaxAlertLevel() {
			return maxAlertLevel;
		}

		@Override
		public BlacklistQueryResult call() throws Exception {
			long startTime = System.currentTimeMillis();
			long startNanos = System.nanoTime();
			// Lookup the MX for the domain
			Lookup mxLookup = new Lookup(domain+'.', Type.MX);
			mxLookup.run();
			if(mxLookup.getResult()!=Lookup.SUCCESSFUL) throw new IOException(domain+": MX lookup failed: "+mxLookup.getErrorString());
			Record[] mxRecords = mxLookup.getAnswers();
			// Pick a random MX
			if(mxRecords.length==0) throw new IOException(domain+": No MX records found");
			MXRecord mx;
			if(mxRecords.length==1) mx = (MXRecord)mxRecords[0];
			else mx = (MXRecord)mxRecords[RootNodeImpl.random.nextInt(mxRecords.length)];
			// Lookup the IP addresses
			Name target = mx.getTarget();
			Lookup aLookup = new Lookup(target, Type.A);
			aLookup.run();
			if(aLookup.getResult()!=Lookup.SUCCESSFUL) throw new IOException(domain+": A lookup failed: "+aLookup.getErrorString());
			Record[] aRecords = aLookup.getAnswers();
			// Pick a random A
			if(aRecords.length==0) throw new IOException(domain+": No A records found");
			ARecord a;
			if(aRecords.length==1) a = (ARecord)aRecords[0];
			else a = (ARecord)aRecords[RootNodeImpl.random.nextInt(aRecords.length)];
			InetAddress address = a.getAddress();
			// Make call from the daemon from privileged port
			Device device = ipAddress.getDevice();
			if(device==null) throw new SQLException(ipAddress+": Device not found");
			Server linuxServer = device.getHost().getLinuxServer();
			if(linuxServer==null) throw new SQLException(ipAddress+": Server not found");
			com.aoindustries.net.InetAddress addressIp = com.aoindustries.net.InetAddress.valueOf(address.getHostAddress());
			String statusLine = linuxServer.checkSmtpBlacklist(ipAddress.getInetAddress(), addressIp);
			// Return results
			long endNanos = System.nanoTime();
			AlertLevel alertLevel;
			if(statusLine.startsWith("220 ")) alertLevel = AlertLevel.NONE;
			else alertLevel = maxAlertLevel;
			return new BlacklistQueryResult(domain, startTime, endNanos-startNanos, addressIp.toString(), statusLine, alertLevel);
		}

		@Override
		String getQuery() {
			return unknownQuery;
		}
	}

	/**
	 * One unique worker is made per persistence file (and should match the ipAddress exactly)
	 */
	private static final Map<String, BlacklistsNodeWorker> workerCache = new HashMap<>();
	static BlacklistsNodeWorker getWorker(File persistenceFile, IpAddress ipAddress) throws IOException, SQLException {
		String path = persistenceFile.getCanonicalPath();
		synchronized(workerCache) {
			BlacklistsNodeWorker worker = workerCache.get(path);
			if(worker==null) {
				worker = new BlacklistsNodeWorker(persistenceFile, ipAddress);
				workerCache.put(path, worker);
			} else {
				if(!worker.ipAddress.equals(ipAddress)) throw new AssertionError("worker.ipAddress!=ipAddress: "+worker.ipAddress+"!="+ipAddress);
			}
			return worker;
		}
	}

	// Will use whichever connector first created this worker, even if other accounts connect later.
	final private IpAddress ipAddress;
	final private List<BlacklistLookup> lookups;

	private static DnsBlacklist[] addUnique(DnsBlacklist ... blacklists) {
		Map<String, DnsBlacklist> unique = AoCollections.newHashMap(blacklists.length);
		for(DnsBlacklist blacklist : blacklists) {
			String basename = blacklist.getBaseName();
			DnsBlacklist existing = unique.get(basename);
			if(existing == null) {
				unique.put(basename, blacklist);
			} else {
				// When two exist, must have the same max alert level
				if(blacklist.getMaxAlertLevel() != existing.getMaxAlertLevel()) {
					throw new AssertionError("Blacklist max alert level mismatch: " + basename);
				}
			}
		}
		return unique.values().toArray(new DnsBlacklist[unique.size()]);
	}

	BlacklistsNodeWorker(File persistenceFile, IpAddress ipAddress) throws IOException, SQLException {
		super(persistenceFile);
		this.ipAddress = ipAddress;
		// Build the list of lookups
		DnsBlacklist[] rblBlacklists = addUnique(
			// <editor-fold desc="bgp.he.net" defaultstate="collapsed">
			// From https://bgp.he.net/ip/66.160.183.1#_rbl on 2021-04-05
			new DnsBlacklist("access.redhawk.org"),
			new DnsBlacklist("all.spamblock.unit.liu.se"),
			new DnsBlacklist("b.barracudacentral.org"),
			new DnsBlacklist("bl.deadbeef.com"),
			new DnsBlacklist("bl.spamcop.net"),
			new DnsBlacklist("blackholes.five-ten-sg.com"),
			new DnsBlacklist("blackholes.mail-abuse.org"),
			new DnsBlacklist("blacklist.sci.kun.nl"),
			new DnsBlacklist("blacklist.woody.ch"),
			new DnsBlacklist("bogons.cymru.com"),
			new DnsBlacklist("bsb.spamlookup.net"),
			new DnsBlacklist("cbl.abuseat.org"),
			new DnsBlacklist("cbl.anti-spam.org.cn"),
			new DnsBlacklist("cblless.anti-spam.org.cn"),
			new DnsBlacklist("cblplus.anti-spam.org.cn"),
			new DnsBlacklist("cdl.anti-spam.org.cn"),
			new DnsBlacklist("combined.rbl.msrbl.net"),
			new DnsBlacklist("csi.cloudmark.com"),
			new DnsBlacklist("db.wpbl.info"),
			new DnsBlacklist("dialups.mail-abuse.org"),
			new DnsBlacklist("dnsbl-1.uceprotect.net"),
			new DnsBlacklist("dnsbl-2.uceprotect.net"),
			new DnsBlacklist("dnsbl-3.uceprotect.net"),
			new DnsBlacklist("dnsbl.abuse.ch"),
			new DnsBlacklist("dnsbl.cyberlogic.net"),
			new DnsBlacklist("dnsbl.dronebl.org"),
			new DnsBlacklist("dnsbl.inps.de"),
			new DnsBlacklist("dnsbl.kempt.net"),
			new DnsBlacklist("dnsbl.sorbs.net"),
			new DnsBlacklist("dob.sibl.support-intelligence.net"),
			new DnsBlacklist("drone.abuse.ch"),
			new DnsBlacklist("dsn.rfc-ignorant.org"),
			new DnsBlacklist("duinv.aupads.org"),
			new DnsBlacklist("dul.blackhole.cantv.net"),
			new DnsBlacklist("dul.dnsbl.sorbs.net"),
			new DnsBlacklist("dul.ru"),
			new DnsBlacklist("dyna.spamrats.com"),
			new DnsBlacklist("dynablock.sorbs.net"),
			new DnsBlacklist("dyndns.rbl.jp"),
			new DnsBlacklist("dynip.rothen.com"),
			new DnsBlacklist("forbidden.icm.edu.pl"),
			new DnsBlacklist("http.dnsbl.sorbs.net"),
			new DnsBlacklist("httpbl.abuse.ch"),
			new DnsBlacklist("images.rbl.msrbl.net"),
			new DnsBlacklist("ips.backscatterer.org"),
			new DnsBlacklist("ix.dnsbl.manitu.net"),
			new DnsBlacklist("korea.services.net"),
			new DnsBlacklist("mail.people.it"),
			new DnsBlacklist("misc.dnsbl.sorbs.net"),
			new DnsBlacklist("multi.surbl.org"),
			new DnsBlacklist("netblock.pedantic.org"),
			new DnsBlacklist("noptr.spamrats.com"),
			new DnsBlacklist("opm.tornevall.org"),
			new DnsBlacklist("orvedb.aupads.org"),
			new DnsBlacklist("pbl.spamhaus.org"),
			new DnsBlacklist("phishing.rbl.msrbl.net"),
			new DnsBlacklist("psbl.surriel.com"),
			new DnsBlacklist("query.senderbase.org"),
			new DnsBlacklist("rbl-plus.mail-abuse.org"),
			new DnsBlacklist("rbl.efnetrbl.org"),
			new DnsBlacklist("rbl.interserver.net"),
			new DnsBlacklist("rbl.spamlab.com"),
			new DnsBlacklist("rbl.suresupport.com"),
			new DnsBlacklist("relays.bl.gweep.ca"),
			new DnsBlacklist("relays.bl.kundenserver.de"),
			new DnsBlacklist("relays.mail-abuse.org"),
			new DnsBlacklist("relays.nether.net"),
			new DnsBlacklist("residential.block.transip.nl"),
			new DnsBlacklist("rot.blackhole.cantv.net"),
			new DnsBlacklist("sbl.spamhaus.org"),
			new DnsBlacklist("short.rbl.jp"),
			new DnsBlacklist("smtp.dnsbl.sorbs.net"),
			new DnsBlacklist("socks.dnsbl.sorbs.net"),
			new DnsBlacklist("spam.abuse.ch"),
			new DnsBlacklist("spam.dnsbl.sorbs.net"),
			new DnsBlacklist("spam.rbl.msrbl.net"),
			new DnsBlacklist("spam.spamrats.com"),
			new DnsBlacklist("spamguard.leadmon.net"),
			new DnsBlacklist("spamlist.or.kr"),
			new DnsBlacklist("spamrbl.imp.ch"),
			new DnsBlacklist("tor.dan.me.uk"),
			new DnsBlacklist("ubl.lashback.com"),
			new DnsBlacklist("ubl.unsubscore.com"),
			new DnsBlacklist("uribl.swinog.ch"),
			new DnsBlacklist("url.rbl.jp"),
			new DnsBlacklist("virbl.bit.nl"),
			new DnsBlacklist("virus.rbl.jp"),
			new DnsBlacklist("virus.rbl.msrbl.net"),
			new DnsBlacklist("web.dnsbl.sorbs.net"),
			new DnsBlacklist("wormrbl.imp.ch"),
			new DnsBlacklist("xbl.spamhaus.org"),
			new DnsBlacklist("zen.spamhaus.org"),
			new DnsBlacklist("zombie.dnsbl.sorbs.net"),
			// </editor-fold>

			// <editor-fold desc="cqcounter.com" defaultstate="collapsed">
			// From http://cqcounter.com/rbl_check/ on 2014-02-09
			new DnsBlacklist("access.redhawk.org"),
			new DnsBlacklist("assholes.madscience.nl"),
			new DnsBlacklist("badconf.rhsbl.sorbs.net"),
			new DnsBlacklist("bl.deadbeef.com"),
			// Offline 2019-12-05: new DnsBlacklist("bl.spamcannibal.org"),
			// Offline 2014-06-28: new DnsBlacklist("bl.technovision.dk"),
			new DnsBlacklist("blackholes.five-ten-sg.com"),
			new DnsBlacklist("blackholes.intersil.net"),
			new DnsBlacklist("blackholes.mail-abuse.org"),
			new DnsBlacklist("blackholes.sandes.dk"),
			new DnsBlacklist("blacklist.sci.kun.nl"),
			new DnsBlacklist("blacklist.spambag.org", AlertLevel.NONE),
			new DnsBlacklist("block.dnsbl.sorbs.net"),
			new DnsBlacklist("blocked.hilli.dk"),
			new DnsBlacklist("cart00ney.surriel.com"),
			new DnsBlacklist("cbl.abuseat.org"),
			new DnsBlacklist("dev.null.dk"),
			new DnsBlacklist("dialup.blacklist.jippg.org"),
			new DnsBlacklist("dialups.mail-abuse.org"),
			new DnsBlacklist("dialups.visi.com"),
			new DnsBlacklist("dnsbl-1.uceprotect.net"),
			new DnsBlacklist("dnsbl-2.uceprotect.net"),
			new DnsBlacklist("dnsbl-3.uceprotect.net"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.ahbl.org"),
			new DnsBlacklist("dnsbl.antispam.or.id"),
			new DnsBlacklist("dnsbl.cyberlogic.net"),
			new DnsBlacklist("dnsbl.inps.de"),
			new DnsBlacklist("uribl.swinog.ch"),
			new DnsBlacklist("dnsbl.kempt.net"),
			// Offline 2014-06-28: new DnsBlacklist("dnsbl.njabl.org"),
			new DnsBlacklist("dnsbl.sorbs.net"),
			// Offline 2014-06-28: new DnsBlacklist("list.dsbl.org"),
			// Offline 2014-06-28: new DnsBlacklist("multihop.dsbl.org"),
			// Offline 2014-06-28: new DnsBlacklist("unconfirmed.dsbl.org"),
			new DnsBlacklist("dsbl.dnsbl.net.au"),
			new DnsBlacklist("duinv.aupads.org"),
			new DnsBlacklist("dul.dnsbl.sorbs.net"),
			// Offline 2014-06-27: new DnsBlacklist("dul.maps.vix.com"),
			new DnsBlacklist("dul.orca.bc.ca"),
			new DnsBlacklist("dul.ru"),
			// Offline 2014-06-27: new DnsBlacklist("dun.dnsrbl.net"),
			// Offline 2014-06-27: new DnsBlacklist("fl.chickenboner.biz"),
			new DnsBlacklist("forbidden.icm.edu.pl"),
			new DnsBlacklist("hil.habeas.com"),
			new DnsBlacklist("http.dnsbl.sorbs.net"),
			new DnsBlacklist("images.rbl.msrbl.net"),
			new DnsBlacklist("intruders.docs.uu.se"),
			new DnsBlacklist("ix.dnsbl.manitu.net"),
			new DnsBlacklist("korea.services.net"),
			new DnsBlacklist("l1.spews.dnsbl.sorbs.net"),
			new DnsBlacklist("l2.spews.dnsbl.sorbs.net"),
			new DnsBlacklist("mail-abuse.blacklist.jippg.org"),
			// Offline 2014-06-28: new DnsBlacklist("map.spam-rbl.com"),
			new DnsBlacklist("misc.dnsbl.sorbs.net"),
			new DnsBlacklist("msgid.bl.gweep.ca"),
			new DnsBlacklist("combined.rbl.msrbl.net"),
			new DnsBlacklist("no-more-funn.moensted.dk"),
			new DnsBlacklist("nomail.rhsbl.sorbs.net"),
			new DnsBlacklist("ohps.dnsbl.net.au"),
			// Disabled 2012-02-07: new DnsBlacklist("okrelays.nthelp.com"),
			new DnsBlacklist("omrs.dnsbl.net.au"),
			new DnsBlacklist("orid.dnsbl.net.au"),
			new DnsBlacklist("orvedb.aupads.org"),
			new DnsBlacklist("osps.dnsbl.net.au"),
			new DnsBlacklist("osrs.dnsbl.net.au"),
			new DnsBlacklist("owfs.dnsbl.net.au"),
			new DnsBlacklist("owps.dnsbl.net.au"),
			new DnsBlacklist("pdl.dnsbl.net.au"),
			new DnsBlacklist("phishing.rbl.msrbl.net"),
			new DnsBlacklist("probes.dnsbl.net.au"),
			new DnsBlacklist("proxy.bl.gweep.ca"),
			new DnsBlacklist("psbl.surriel.com"),
			new DnsBlacklist("pss.spambusters.org.ar"),
			new DnsBlacklist("rbl-plus.mail-abuse.org"),
			// Shutdown on 2009-11-11: new DnsBlacklist("rbl.cluecentral.net"),
			new DnsBlacklist("rbl.efnetrbl.org"),
			new DnsBlacklist("rbl.jp"),
			// Offline 2014-06-27: new DnsBlacklist("rbl.maps.vix.com"),
			new DnsBlacklist("rbl.schulte.org"),
			new DnsBlacklist("rbl.snark.net"),
			new DnsBlacklist("rbl.triumf.ca"),
			new DnsBlacklist("rdts.dnsbl.net.au"),
			new DnsBlacklist("relays.bl.gweep.ca"),
			new DnsBlacklist("relays.bl.kundenserver.de"),
			new DnsBlacklist("relays.mail-abuse.org"),
			new DnsBlacklist("relays.nether.net"),
			// Disabled 2012-02-07: new DnsBlacklist("relays.nthelp.com"),
			new DnsBlacklist("rhsbl.sorbs.net"),
			new DnsBlacklist("ricn.dnsbl.net.au"),
			new DnsBlacklist("rmst.dnsbl.net.au"),
			new DnsBlacklist("rsbl.aupads.org"),
			// Shutdown on 2009-11-11: new DnsBlacklist("satos.rbl.cluecentral.net"),
			new DnsBlacklist("sbl-xbl.spamhaus.org"),
			new DnsBlacklist("sbl.spamhaus.org"),
			new DnsBlacklist("smtp.dnsbl.sorbs.net"),
			new DnsBlacklist("socks.dnsbl.sorbs.net"),
			new DnsBlacklist("sorbs.dnsbl.net.au"),
			new DnsBlacklist("spam.dnsbl.sorbs.net"),
			// Offline 2014-06-27: new DnsBlacklist("spam.dnsrbl.net"),
			new DnsBlacklist("spam.olsentech.net"),
			// Offline 2014-06-27: new DnsBlacklist("spam.wytnij.to"),
			new DnsBlacklist("bl.spamcop.net"),
			new DnsBlacklist("spamguard.leadmon.net"),
			new DnsBlacklist("spamsites.dnsbl.net.au"),
			// Shutdown 2012-02-07: new DnsBlacklist("spamsources.dnsbl.info"),
			new DnsBlacklist("spamsources.fabel.dk"),
			new DnsBlacklist("spews.dnsbl.net.au"),
			new DnsBlacklist("t1.dnsbl.net.au"),
			new DnsBlacklist("tor.dan.me.uk"),
			new DnsBlacklist("torexit.dan.me.uk"),
			new DnsBlacklist("ucepn.dnsbl.net.au"),
			new DnsBlacklist("virbl.dnsbl.bit.nl"),
			new DnsBlacklist("virus.rbl.msrbl.net"),
			new DnsBlacklist("virus.rbl.jp"),
			new DnsBlacklist("web.dnsbl.sorbs.net"),
			new DnsBlacklist("whois.rfc-ignorant.org"),
			// Offline 2014-06-26: new DnsBlacklist("will-spam-for-food.eu.org"),
			new DnsBlacklist("xbl.spamhaus.org"),
			new DnsBlacklist("zombie.dnsbl.sorbs.net"),
			// </editor-fold>

			// <editor-fold desc="robtex.com" defaultstate="collapsed">
			// From https://www.robtex.com/ip/66.160.183.2.html#blacklists on 2014-02-09
			// Not including "timeout", "servfail", "not whitelisted" sections.
			// Only including "green" section
			// Could add whitelists here
			new DnsBlacklist("access.redhawk.org"),
			new DnsBlacklist("all.spamrats.com"),
			new DnsBlacklist("assholes.madscience.nl"),
			new DnsBlacklist("b.barracudacentral.org"),
			// Removed 2014-02-09: new DnsBlacklist("bl.csma.biz"),
			// Offline 2019-12-05: new DnsBlacklist("bl.spamcannibal.org"),
			// Offline 2014-06-28: new DnsBlacklist("bl.technovision.dk"),
			// Removed 2014-02-09: new DnsBlacklist("black.uribl.com"),
			new DnsBlacklist("blackholes.five-ten-sg.com"),
			new DnsBlacklist("blackholes.intersil.net"),
			new DnsBlacklist("blackholes.mail-abuse.org"),
			new DnsBlacklist("blackholes.sandes.dk"),
			new DnsBlacklist("blacklist.sci.kun.nl"),
			new DnsBlacklist("block.dnsbl.sorbs.net"),
			new DnsBlacklist("blocked.hilli.dk"),
			// Removed 2014-02-09: new DnsBlacklist("blocklist.squawk.com"),
			// Removed 2014-02-09: new DnsBlacklist("blocklist2.squawk.com"),
			new DnsBlacklist("cart00ney.surriel.com"),
			new DnsBlacklist("cbl.abuseat.org"),
			new DnsBlacklist("cblless.anti-spam.org.cn"),
			new DnsBlacklist("cblplus.anti-spam.org.cn"),
			new DnsBlacklist("cdl.anti-spam.org.cn"),
			new DnsBlacklist("combined.abuse.ch"),
			new DnsBlacklist("dev.null.dk"),
			new DnsBlacklist("dialup.blacklist.jippg.org"),
			new DnsBlacklist("dialups.mail-abuse.org"),
			new DnsBlacklist("dialups.visi.com"),
			new DnsBlacklist("dnsbl-1.uceprotect.net"),
			new DnsBlacklist("dnsbl-2.uceprotect.net"),
			new DnsBlacklist("dnsbl-3.uceprotect.net"),
			new DnsBlacklist("dnsbl.abuse.ch"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.ahbl.org"),
			new DnsBlacklist("dnsbl.antispam.or.id"),
			new DnsBlacklist("dnsbl.dronebl.org"),
			new DnsBlacklist("dnsbl.inps.de"),
			new DnsBlacklist("dnsbl.kempt.net"),
			// Offline 2014-06-28: new DnsBlacklist("dnsbl.njabl.org"),
			new DnsBlacklist("dnsbl.mags.net"),
			new DnsBlacklist("dnsbl.sorbs.net"),
			new DnsBlacklist("drone.abuse.ch"),
			new DnsBlacklist("dsbl.dnsbl.net.au"),
			new DnsBlacklist("duinv.aupads.org"),
			new DnsBlacklist("dul.dnsbl.sorbs.net"),
			new DnsBlacklist("dul.orca.bc.ca"),
			new DnsBlacklist("dul.ru"),
			// Offline 2014-06-28: new DnsBlacklist("dynablock.njabl.org"),
			// Removed 2014-02-09: new DnsBlacklist("fl.chickenboner.biz"),
			new DnsBlacklist("forbidden.icm.edu.pl"),
			// Removed 2014-02-09: new DnsBlacklist("grey.uribl.com"),
			new DnsBlacklist("hil.habeas.com"),
			new DnsBlacklist("http.dnsbl.sorbs.net"),
			// Not a useful basename: new DnsBlacklist("images-msrbls"),
			new DnsBlacklist("intruders.docs.uu.se"),
			new DnsBlacklist("ips.backscatterer.org"),
			new DnsBlacklist("ix.dnsbl.manitu.net"),
			new DnsBlacklist("korea.services.net"),
			new DnsBlacklist("l1.spews.dnsbl.sorbs.net"),
			new DnsBlacklist("l2.spews.dnsbl.sorbs.net"),
			new DnsBlacklist("list.quorum.to"),
			new DnsBlacklist("mail-abuse.blacklist.jippg.org"),
			// Removed 2014-02-09: new DnsBlacklist("map.spam-rbl.com"),
			new DnsBlacklist("misc.dnsbl.sorbs.net"),
			new DnsBlacklist("msgid.bl.gweep.ca"),
			// Not a useful basename: new DnsBlacklist("msrbl"),
			new DnsBlacklist("multi.surbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("multi.uribl.com"),
			new DnsBlacklist("netblock.pedantic.org"),
			new DnsBlacklist("no-more-funn.moensted.dk"),
			new DnsBlacklist("noptr.spamrats.com"),
			new DnsBlacklist("ohps.dnsbl.net.au"),
			// Disabled 2012-02-07: new DnsBlacklist("okrelays.nthelp.com"),
			new DnsBlacklist("omrs.dnsbl.net.au"),
			new DnsBlacklist("opm.blitzed.org"),
			new DnsBlacklist("opm.tornevall.org"),
			new DnsBlacklist("orid.dnsbl.net.au"),
			new DnsBlacklist("orvedb.aupads.org"),
			new DnsBlacklist("osps.dnsbl.net.au"),
			new DnsBlacklist("osrs.dnsbl.net.au"),
			new DnsBlacklist("owfs.dnsbl.net.au"),
			new DnsBlacklist("owps.dnsbl.net.au"),
			new DnsBlacklist("pbl.spamhaus.org"),
			new DnsBlacklist("pdl.dnsbl.net.au"),
			// Not a useful basename: new DnsBlacklist("phishing-msrbl"),
			new DnsBlacklist("probes.dnsbl.net.au"),
			new DnsBlacklist("problems.dnsbl.sorbs.net"),
			// Not a useful basename: new DnsBlacklist("Project Honeypot"),
			new DnsBlacklist("proxy.bl.gweep.ca"),
			new DnsBlacklist("psbl.surriel.com"),
			new DnsBlacklist("pss.spambusters.org.ar"),
			// Shutdown on 2009-11-11: new DnsBlacklist("rbl.cluecentral.net"),
			new DnsBlacklist("rbl.efnetrbl.org"),
			new DnsBlacklist("rbl.jp"),
			// Removed 2014-02-09: new DnsBlacklist("rbl.maps.vix.com"),
			new DnsBlacklist("rbl.schulte.org"),
			new DnsBlacklist("rbl.snark.net"),
			// Removed 2014-02-09: new DnsBlacklist("rbl.triumf.ca"),
			new DnsBlacklist("rbl-plus.mail-abuse.org"),
			new DnsBlacklist("rdts.dnsbl.net.au"),
			// Removed 2014-02-09: new DnsBlacklist("red.uribl.com"),
			new DnsBlacklist("relays.bl.gweep.ca"),
			new DnsBlacklist("relays.bl.kundenserver.de"),
			new DnsBlacklist("relays.mail-abuse.org"),
			new DnsBlacklist("relays.nether.net"),
			// Disabled 2012-02-07: new DnsBlacklist("relays.nthelp.com"),
			new DnsBlacklist("rhsbl.sorbs.net"),
			new DnsBlacklist("ricn.dnsbl.net.au"),
			new DnsBlacklist("rmst.dnsbl.net.au"),
			new DnsBlacklist("rsbl.aupads.org"),
			new DnsBlacklist("safe.dnsbl.sorbs.net"),
			// Shutdown on 2009-11-11: new DnsBlacklist("satos.rbl.cluecentral.net"),
			new DnsBlacklist("sbl-xbl.spamhaus.org"),
			// Removed 2014-02-09: new DnsBlacklist("sbl.csma.biz"),
			new DnsBlacklist("sbl.spamhaus.org"),
			new DnsBlacklist("smtp.dnsbl.sorbs.net"),
			new DnsBlacklist("socks.dnsbl.sorbs.net"),
			new DnsBlacklist("sorbs.dnsbl.net.au"),
			// Removed 2014-07-14, Timeout and SERVFAIL only: new DnsBlacklist("spam.abuse.ch"),
			new DnsBlacklist("spam.dnsbl.sorbs.net"),
			new DnsBlacklist("spam.olsentech.net"),
			new DnsBlacklist("spam.pedantic.org"),
			// Removed 2014-02-09: new DnsBlacklist("spam.wytnij.to"),
			// Not a useful basename: new DnsBlacklist("spamcop"),
			new DnsBlacklist("spamguard.leadmon.net"),
			new DnsBlacklist("spamsites.dnsbl.net.au"),
			new DnsBlacklist("spamsources.fabel.dk"),
			new DnsBlacklist("spews.dnsbl.net.au"),
			new DnsBlacklist("t1.dnsbl.net.au"),
			new DnsBlacklist("tor.dan.me.uk"),
			new DnsBlacklist("torexit.dan.me.uk"),
			new DnsBlacklist("ucepn.dnsbl.net.au"),
			new DnsBlacklist("uribl.swinog.ch"),
			// Not a useful basename: new DnsBlacklist("virbl"),
			// Not a useful basename: new DnsBlacklist("virus-msrbl"),
			new DnsBlacklist("virus.rbl.jp"),
			new DnsBlacklist("web.dnsbl.sorbs.net"),
			// Removed 2014-02-09: new DnsBlacklist("will-spam-for-food.eu.org"),
			new DnsBlacklist("xbl.spamhaus.org"),
			new DnsBlacklist("zen.spamhaus.org"),
			new DnsBlacklist("zombie.dnsbl.sorbs.net"),
			// </editor-fold>

			// <editor-fold desc="anti-abuse.org" defaultstate="collapsed">
			// From www.anti-abuse.org on 2009-11-06
			new DnsBlacklist("bl.spamcop.net"),
			new DnsBlacklist("cbl.abuseat.org"),
			new DnsBlacklist("b.barracudacentral.org"),
			new DnsBlacklist("dnsbl.sorbs.net"),
			new DnsBlacklist("http.dnsbl.sorbs.net"),
			new DnsBlacklist("dul.dnsbl.sorbs.net"),
			new DnsBlacklist("misc.dnsbl.sorbs.net"),
			new DnsBlacklist("smtp.dnsbl.sorbs.net"),
			new DnsBlacklist("socks.dnsbl.sorbs.net"),
			new DnsBlacklist("spam.dnsbl.sorbs.net"),
			new DnsBlacklist("web.dnsbl.sorbs.net"),
			new DnsBlacklist("zombie.dnsbl.sorbs.net"),
			new DnsBlacklist("dnsbl-1.uceprotect.net"),
			new DnsBlacklist("dnsbl-2.uceprotect.net"),
			new DnsBlacklist("dnsbl-3.uceprotect.net"),
			new DnsBlacklist("pbl.spamhaus.org"),
			new DnsBlacklist("sbl.spamhaus.org"),
			new DnsBlacklist("xbl.spamhaus.org"),
			new DnsBlacklist("zen.spamhaus.org"),
			// Removed 2014-02-09: new DnsBlacklist("images.rbl.msrbl.net"),
			// Removed 2014-02-09: new DnsBlacklist("phishing.rbl.msrbl.net"),
			// Removed 2014-02-09: new DnsBlacklist("combined.rbl.msrbl.net"),
			// Removed 2014-02-09: new DnsBlacklist("spam.rbl.msrbl.net"),
			// Removed 2014-02-09: new DnsBlacklist("virus.rbl.msrbl.net"),
			// Offline 2019-12-05: new DnsBlacklist("bl.spamcannibal.org"),
			new DnsBlacklist("psbl.surriel.com"),
			new DnsBlacklist("ubl.unsubscore.com"),
			// Offline 2014-06-28: new DnsBlacklist("dnsbl.njabl.org"),
			// Offline 2014-06-28: new DnsBlacklist("combined.njabl.org"),
			new DnsBlacklist("rbl.spamlab.com"),
			// Removed 2014-02-09: new DnsBlacklist("bl.deadbeef.com"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.ahbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("tor.ahbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("ircbl.ahbl.org"),
			new DnsBlacklist("dyna.spamrats.com"),
			new DnsBlacklist("noptr.spamrats.com"),
			new DnsBlacklist("spam.spamrats.com"),
			// Removed 2014-02-09: new DnsBlacklist("blackholes.five-ten-sg.com"),
			// Removed 2014-02-09: new DnsBlacklist("bl.emailbasura.org"),
			new DnsBlacklist("cbl.anti-spam.org.cn"),
			new DnsBlacklist("cdl.anti-spam.org.cn"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.cyberlogic.net"),
			new DnsBlacklist("dnsbl.inps.de"),
			new DnsBlacklist("drone.abuse.ch"),
			// Removed 2014-02-09: new DnsBlacklist("spam.abuse.ch"),
			new DnsBlacklist("httpbl.abuse.ch"),
			new DnsBlacklist("dul.ru"),
			new DnsBlacklist("korea.services.net"),
			new DnsBlacklist("short.rbl.jp"),
			new DnsBlacklist("virus.rbl.jp"),
			new DnsBlacklist("spamrbl.imp.ch"),
			new DnsBlacklist("wormrbl.imp.ch"),
			new DnsBlacklist("virbl.bit.nl"),
			new DnsBlacklist("rbl.suresupport.com"),
			new DnsBlacklist("dsn.rfc-ignorant.org"),
			new DnsBlacklist("ips.backscatterer.org"),
			new DnsBlacklist("spamguard.leadmon.net"),
			new DnsBlacklist("opm.tornevall.org"),
			new DnsBlacklist("netblock.pedantic.org"),
			new DnsBlacklist("multi.surbl.org"),
			new DnsBlacklist("ix.dnsbl.manitu.net"),
			new DnsBlacklist("tor.dan.me.uk"),
			new DnsBlacklist("rbl.efnetrbl.org"),
			new DnsBlacklist("relays.mail-abuse.org"),
			new DnsBlacklist("blackholes.mail-abuse.org"),
			new DnsBlacklist("rbl-plus.mail-abuse.org"),
			new DnsBlacklist("dnsbl.dronebl.org"),
			new DnsBlacklist("access.redhawk.org"),
			new DnsBlacklist("db.wpbl.info"),
			new DnsBlacklist("rbl.interserver.net"),
			new DnsBlacklist("query.senderbase.org"),
			new DnsBlacklist("bogons.cymru.com"),
			new DnsBlacklist("csi.cloudmark.com"),
			// </editor-fold>

			// <editor-fold desc="multirbl.valli.org" defaultstate="collapsed">
			// From http://multirbl.valli.org/ on 2014-02-09
			new DnsBlacklist("0spam.fusionzero.com"),
			new DnsBlacklist("0spam-killlist.fusionzero.com"),
			// Removed 2014-02-09: new DnsBlacklist("blackholes.five-ten-sg.com"),
			new DnsBlacklist("combined.abuse.ch"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.abuse.ch"),
			new DnsBlacklist("drone.abuse.ch"),
			// Removed 2014-07-14, Timeout and SERVFAIL only: new DnsBlacklist("spam.abuse.ch"),
			new DnsBlacklist("httpbl.abuse.ch"),
			// Forward lookup: uribl.zeustracker.abuse.ch
			new DnsBlacklist("ipbl.zeustracker.abuse.ch"),
			new DnsBlacklist("rbl.abuse.ro"),
			// Forward lookup: uribl.abuse.ro
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.ahbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("ircbl.ahbl.org"),
			// Forward lookup: rhsbl.ahbl.org
			// Removed 2014-02-09: new DnsBlacklist("orvedb.aupads.org"),
			// Removed 2014-02-09: new DnsBlacklist("rsbl.aupads.org"),
			new DnsBlacklist("spam.dnsbl.anonmails.de"),
			new DnsBlacklist("dnsbl.anticaptcha.net"),
			new DnsBlacklist("orvedb.aupads.org"),
			new DnsBlacklist("rsbl.aupads.org"),
			// Forward lookup: l1.apews.org
			new DnsBlacklist("l2.apews.org"),
			// Removed 2014-02-09: new DnsBlacklist("fresh.dict.rbl.arix.com"),
			// Removed 2014-02-09: new DnsBlacklist("stale.dict.rbl.arix.com"),
			// Removed 2014-02-09: new DnsBlacklist("fresh.sa_slip.rbl.arix.com"),
			// Removed 2014-02-09: new DnsBlacklist("stale.sa_slip.arix.com"),
			new DnsBlacklist("aspews.ext.sorbs.net"),
			new DnsBlacklist("dnsbl.aspnet.hu"),
			// Forward lookup: dnsbl.aspnet.hu
			// Removed 2014-02-09: new DnsBlacklist("access.atlbl.net"),
			// Removed 2014-02-09: new DnsBlacklist("rbl.atlbl.net"),
			new DnsBlacklist("ips.backscatterer.org"),
			new DnsBlacklist("b.barracudacentral.org"),
			new DnsBlacklist("bb.barracudacentral.org"),
			new DnsBlacklist("list.bbfh.org"),
			new DnsBlacklist("l1.bbfh.ext.sorbs.net"),
			new DnsBlacklist("l2.bbfh.ext.sorbs.net"),
			new DnsBlacklist("l3.bbfh.ext.sorbs.net"),
			new DnsBlacklist("l4.bbfh.ext.sorbs.net"),
			new DnsBlacklist("bbm.2ch.net", AlertLevel.NONE), // Japanese site, don't know how to delist
			new DnsBlacklist("niku.2ch.net", AlertLevel.NONE), // Japanese site, don't know how to delist
			new DnsBlacklist("bbx.2ch.net", AlertLevel.NONE), // Japanese site, don't know how to delist
			// Removed 2014-02-09: new DnsBlacklist("bl.deadbeef.com"),
			// Removed 2014-02-09: new DnsBlacklist("rbl.blakjak.net"),
			new DnsBlacklist("netscan.rbl.blockedservers.com"),
			new DnsBlacklist("rbl.blockedservers.com"),
			new DnsBlacklist("spam.rbl.blockedservers.com"),
			new DnsBlacklist("list.blogspambl.com"),
			new DnsBlacklist("bsb.empty.us"),
			// Forward lookup: bsb.empty.us
			new DnsBlacklist("bsb.spamlookup.net"),
			// Forward lookup: bsb.spamlookup.net
			// Domain expired 2018-03-27: new DnsBlacklist("dnsbl.burnt-tech.com"),
			new DnsBlacklist("blacklist.sci.kun.nl"),
			new DnsBlacklist("cbl.anti-spam.org.cn"),
			new DnsBlacklist("cblplus.anti-spam.org.cn"),
			new DnsBlacklist("cblless.anti-spam.org.cn"),
			new DnsBlacklist("cdl.anti-spam.org.cn"),
			new DnsBlacklist("cbl.abuseat.org"),
			// Offline 2014-06-28: new DnsBlacklist("rbl.choon.net"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.cyberlogic.net"),
			new DnsBlacklist("bogons.cymru.com"),
			new DnsBlacklist("v4.fullbogons.cymru.com"),
			new DnsBlacklist("tor.dan.me.uk"),
			new DnsBlacklist("torexit.dan.me.uk"),
			// Forward lookup: ex.dnsbl.org
			// Forward lookup: in.dnsbl.org
			new DnsBlacklist("rbl.dns-servicios.com"),
			new DnsBlacklist("dnsbl.ipocalypse.net"),
			new DnsBlacklist("dnsbl.mags.net"),
			// Forward lookup: dnsbl.othello.ch
			new DnsBlacklist("dnsbl.rv-soft.info"),
			// Offline 2014-06-28: new DnsBlacklist("dnsblchile.org"),
			new DnsBlacklist("vote.drbl.caravan.ru"),
			new DnsBlacklist("vote.drbldf.dsbl.ru"),
			new DnsBlacklist("vote.drbl.gremlin.ru"),
			new DnsBlacklist("work.drbl.caravan.ru"),
			new DnsBlacklist("work.drbldf.dsbl.ru"),
			new DnsBlacklist("work.drbl.gremlin.ru"),
			// Removed 2014-02-09: new DnsBlacklist("vote.drbl.drand.net"),
			// Removed 2014-02-09: new DnsBlacklist("spamprobe.drbl.drand.net"),
			// Removed 2014-02-09: new DnsBlacklist("spamtrap.drbl.drand.net"),
			// Removed 2014-02-09: new DnsBlacklist("work.drbl.drand.net"),
			new DnsBlacklist("bl.drmx.org"),
			new DnsBlacklist("dnsbl.dronebl.org"),
			// Removed 2014-02-09: new DnsBlacklist("rbl.efnethelp.net"),
			new DnsBlacklist("rbl.efnet.org"),
			new DnsBlacklist("rbl.efnetrbl.org"),
			new DnsBlacklist("tor.efnet.org"),
			// Offline 2019-12-05: new DnsBlacklist("bl.emailbasura.org"),
			new DnsBlacklist("rbl.fasthosts.co.uk"),
			new DnsBlacklist("fnrbl.fast.net"),
			new DnsBlacklist("forbidden.icm.edu.pl"),
			new DnsBlacklist("hil.habeas.com"),
			// Offline 2018-03-27: new DnsBlacklist("lookup.dnsbl.iip.lu"),
			new DnsBlacklist("spamrbl.imp.ch"),
			new DnsBlacklist("wormrbl.imp.ch"),
			new DnsBlacklist("dnsbl.inps.de"),
			new DnsBlacklist("intercept.datapacket.net"),
			new DnsBlacklist("rbl.interserver.net"),
			// Offline 2014-06-28: new DnsBlacklist("any.dnsl.ipquery.org"),
			// Offline 2014-06-28: new DnsBlacklist("backscat.dnsl.ipquery.org"),
			// Offline 2014-06-28: new DnsBlacklist("netblock.dnsl.ipquery.org"),
			// Offline 2014-06-28: new DnsBlacklist("relay.dnsl.ipquery.org"),
			// Offline 2014-06-28: new DnsBlacklist("single.dnsl.ipquery.org"),
			new DnsBlacklist("mail-abuse.blacklist.jippg.org"),
			// Removed 2014-02-09: new DnsBlacklist("karmasphere.email-sender.dnsbl.karmasphere.com"),
			new DnsBlacklist("dnsbl.justspam.org"),
			new DnsBlacklist("dnsbl.kempt.net"),
			new DnsBlacklist("spamlist.or.kr"),
			new DnsBlacklist("bl.konstant.no"),
			new DnsBlacklist("relays.bl.kundenserver.de"),
			new DnsBlacklist("spamguard.leadmon.net"),
			// Removed 2014-02-09: new DnsBlacklist("fraud.rhs.mailpolice.com"),
			// Removed 2014-02-09: new DnsBlacklist("sbl.csma.biz"),
			// Removed 2014-02-09: new DnsBlacklist("bl.csma.biz"),
			new DnsBlacklist("dnsbl.madavi.de"),
			// Offline 2018-03-27: new DnsBlacklist("ipbl.mailhosts.org"),
			// Forward lookup: rhsbl.mailhosts.org
			// Offline 2018-03-27: new DnsBlacklist("shortlist.mailhosts.org"),
			new DnsBlacklist("bl.mailspike.net"),
			new DnsBlacklist("z.mailspike.net"),
			new DnsBlacklist("bl.mav.com.br"),
			new DnsBlacklist("cidr.bl.mcafee.com"),
			// Offline 2019-12-05: new DnsBlacklist("rbl.megarbl.net"),
			new DnsBlacklist("combined.rbl.msrbl.net"),
			new DnsBlacklist("images.rbl.msrbl.net"),
			new DnsBlacklist("phishing.rbl.msrbl.net"),
			new DnsBlacklist("spam.rbl.msrbl.net"),
			new DnsBlacklist("virus.rbl.msrbl.net"),
			new DnsBlacklist("web.rbl.msrbl.net"),
			new DnsBlacklist("relays.nether.net"),
			new DnsBlacklist("unsure.nether.net"),
			new DnsBlacklist("ix.dnsbl.manitu.net"),
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.njabl.org"),
			// Removed 2014-02-09: new DnsBlacklist("bhnc.njabl.org"),
			// Removed 2014-02-09: new DnsBlacklist("combined.njabl.org"),
			new DnsBlacklist("no-more-funn.moensted.dk"),
			new DnsBlacklist("nospam.ant.pl"),
			new DnsBlacklist("dyn.nszones.com"),
			new DnsBlacklist("sbl.nszones.com"),
			new DnsBlacklist("bl.nszones.com"),
			// Forward lookup: ubl.nszones.com
			// Removed 2021-04-05: new DnsBlacklist("dnsbl.openresolvers.org"),
			new DnsBlacklist("rbl.orbitrbl.com", AlertLevel.NONE),
			new DnsBlacklist("netblock.pedantic.org"),
			new DnsBlacklist("spam.pedantic.org"),
			new DnsBlacklist("pofon.foobar.hu"),
			new DnsBlacklist("rbl.polarcomm.net"),
			new DnsBlacklist("dnsbl.proxybl.org"),
			new DnsBlacklist("psbl.surriel.com"),
			new DnsBlacklist("all.rbl.jp"),
			// Forward lookup: dyndns.rbl.jp
			new DnsBlacklist("short.rbl.jp"),
			// Forward lookup: url.rbl.jp
			new DnsBlacklist("virus.rbl.jp"),
			new DnsBlacklist("rbl.schulte.org"),
			new DnsBlacklist("rbl.talkactive.net"),
			new DnsBlacklist("access.redhawk.org"),
			new DnsBlacklist("dnsbl.rizon.net"),
			new DnsBlacklist("dynip.rothen.com"),
			new DnsBlacklist("dul.ru"),
			new DnsBlacklist("dnsbl.rymsho.ru"),
			// Forward lookup: rhsbl.rymsho.ru
			new DnsBlacklist("all.s5h.net"),
			new DnsBlacklist("tor.dnsbl.sectoor.de"),
			new DnsBlacklist("exitnodes.tor.dnsbl.sectoor.de"),
			new DnsBlacklist("bl.score.senderscore.com"),
			// Removed 2015-06-26: new DnsBlacklist("bl.shlink.org"),
			// Removed 2015-06-26: new DnsBlacklist("dyn.shlink.org"),
			// Forward lookup: rhsbl.shlink.org
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.solid.net"),
			new DnsBlacklist("dnsbl.sorbs.net"),
			new DnsBlacklist("problems.dnsbl.sorbs.net"),
			new DnsBlacklist("proxies.dnsbl.sorbs.net"),
			new DnsBlacklist("relays.dnsbl.sorbs.net"),
			new DnsBlacklist("safe.dnsbl.sorbs.net"),
			// Forward lookup: nomail.rhsbl.sorbs.net
			// Forward lookup: badconf.rhsbl.sorbs.net
			new DnsBlacklist("dul.dnsbl.sorbs.net"),
			new DnsBlacklist("zombie.dnsbl.sorbs.net"),
			new DnsBlacklist("block.dnsbl.sorbs.net"),
			new DnsBlacklist("escalations.dnsbl.sorbs.net"),
			new DnsBlacklist("http.dnsbl.sorbs.net"),
			new DnsBlacklist("misc.dnsbl.sorbs.net"),
			new DnsBlacklist("smtp.dnsbl.sorbs.net"),
			new DnsBlacklist("socks.dnsbl.sorbs.net"),
			// Forward lookup: rhsbl.sorbs.net
			new DnsBlacklist("spam.dnsbl.sorbs.net"),
			new DnsBlacklist("recent.spam.dnsbl.sorbs.net"),
			new DnsBlacklist("new.spam.dnsbl.sorbs.net"),
			new DnsBlacklist("old.spam.dnsbl.sorbs.net"),
			new DnsBlacklist("web.dnsbl.sorbs.net"),
			new DnsBlacklist("korea.services.net"),
			new DnsBlacklist("backscatter.spameatingmonkey.net"),
			new DnsBlacklist("badnets.spameatingmonkey.net"),
			new DnsBlacklist("bl.spameatingmonkey.net"),
			// Forward lookup: fresh.spameatingmonkey.net
			// Forward lookup: fresh10.spameatingmonkey.net
			// Forward lookup: fresh15.spameatingmonkey.net
			new DnsBlacklist("netbl.spameatingmonkey.net"),
			// Forward lookup: uribl.spameatingmonkey.net
			// Forward lookup: urired.spameatingmonkey.net
			// Removed 2014-02-09: new DnsBlacklist("map.spam-rbl.com"),
			new DnsBlacklist("singlebl.spamgrouper.com", AlertLevel.NONE), // Very unprofessional
			new DnsBlacklist("netblockbl.spamgrouper.com", AlertLevel.NONE), // Very unprofessional
			new DnsBlacklist("all.spam-rbl.fr"),
			// Offline 2019-12-05: new DnsBlacklist("bl.spamcannibal.org"),
			new DnsBlacklist("dnsbl.spam-champuru.livedoor.com"),
			new DnsBlacklist("bl.spamcop.net"),
			// Forward lookup: dbl.spamhaus.org
			new DnsBlacklist("pbl.spamhaus.org"),
			new DnsBlacklist("sbl.spamhaus.org"),
			new DnsBlacklist("sbl-xbl.spamhaus.org"),
			new DnsBlacklist("xbl.spamhaus.org"),
			new DnsBlacklist("zen.spamhaus.org"),
			new DnsBlacklist("feb.spamlab.com"),
			new DnsBlacklist("rbl.spamlab.com"),
			new DnsBlacklist("all.spamrats.com"),
			new DnsBlacklist("dyna.spamrats.com"),
			new DnsBlacklist("noptr.spamrats.com"),
			new DnsBlacklist("spam.spamrats.com"),
			new DnsBlacklist("spamsources.fabel.dk"),
			new DnsBlacklist("bl.spamstinks.com"),
			new DnsBlacklist("badhost.stopspam.org"),
			new DnsBlacklist("block.stopspam.org"),
			new DnsBlacklist("dnsbl.stopspam.org"),
			new DnsBlacklist("dul.pacifier.net"),
			// Removed 2014-02-09: new DnsBlacklist("ab.surbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("jp.surbl.org"),
			new DnsBlacklist("multi.surbl.org"),
			// Forward lookup: multi.surbl.org
			// Removed 2014-02-09: new DnsBlacklist("ob.surbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("ph.surbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("sc.surbl.org"),
			// Removed 2014-02-09: new DnsBlacklist("ws.surbl.org"),
			// Deprecated and offline 2018-03-27: new DnsBlacklist("xs.surbl.org"),
			// Forward lookup: xs.surbl.org
			// Disabled 2012-02-07: new DnsBlacklist("dnsbl.swiftbl.net"),
			// Offline 2014-06-28: new DnsBlacklist("dnsbl.swiftbl.org"),
			new DnsBlacklist("dnsrbl.swinog.ch"),
			// Forward lookup: uribl.swinog.ch
			// Offline 2014-06-28: new DnsBlacklist("bl.technovision.dk"),
			new DnsBlacklist("st.technovision.dk"),
			// Forward lookup: dob.sibl.support-intelligence.net
			new DnsBlacklist("opm.tornevall.org"),
			new DnsBlacklist("rbl2.triumf.ca"),
			new DnsBlacklist("truncate.gbudb.net"),
			new DnsBlacklist("dnsbl-0.uceprotect.net"),
			new DnsBlacklist("dnsbl-1.uceprotect.net"),
			new DnsBlacklist("dnsbl-2.uceprotect.net"),
			new DnsBlacklist("dnsbl-3.uceprotect.net"),
			new DnsBlacklist("ubl.unsubscore.com"),
			// Forward lookup: black.uribl.com
			// Forward lookup: grey.uribl.com
			// Forward lookup: multi.uribl.com
			// Forward lookup: red.uribl.com
			// Removed 2014-02-09: new DnsBlacklist("ubl.lashback.com"),
			// Removed 2014-07-02 pending successful test delist requests: new DnsBlacklist("free.v4bl.org"),
			// Removed 2014-07-02 pending successful test delist requests: new DnsBlacklist("ip.v4bl.org"),
			new DnsBlacklist("virbl.dnsbl.bit.nl"),
			new DnsBlacklist("dnsbl.webequipped.com"),
			new DnsBlacklist("blacklist.woody.ch"),
			// Forward lookup: uri.blacklist.woody.ch
			new DnsBlacklist("db.wpbl.info"),
			new DnsBlacklist("bl.blocklist.de"),
			new DnsBlacklist("dnsbl.zapbl.net"),
			// Forward lookup: rhsbl.zapbl.net
			// Forward lookup: zebl.zoneedit.com
			// Forward lookup: ban.zebl.zoneedit.com
			// Removed 2014-02-09: new DnsBlacklist("dnsbl.zetabl.org"),
			new DnsBlacklist("hostkarma.junkemailfilter.com"),
			// Forward lookup: hostkarma.junkemailfilter.com
			new DnsBlacklist("rep.mailspike.net"),
			new DnsBlacklist("list.quorum.to"),
			new DnsBlacklist("srn.surgate.net")
			// </editor-fold>
		);
		//InetAddress ip = ipAddress.getInetAddress();
		Device device;
		IpAddressMonitoring iam;
		boolean checkSmtpBlacklist =
			//!"64.62.174.125".equals(ip)
			//&& !"64.62.174.189".equals(ip)
			//&& !"64.62.174.253".equals(ip)
			//&& !"64.71.144.125".equals(ip)
			//&& !"66.160.183.125".equals(ip)
			//&& !"66.160.183.189".equals(ip)
			//&& !"66.160.183.253".equals(ip)
			((iam = ipAddress.getMonitoring()) != null)
			&& iam.getCheckBlacklistsOverSmtp()
			&& (device = ipAddress.getDevice()) != null
			&& device.getHost().getLinuxServer() != null
		;
		lookups = new ArrayList<>(checkSmtpBlacklist ? (rblBlacklists.length + 6) : rblBlacklists.length);
		lookups.addAll(Arrays.asList(rblBlacklists));
		if(checkSmtpBlacklist) {
			// TODO: Update this list dynamically, since update to ip_addresses.check_blacklists_over_smtp can happen at any time
			lookups.add(new SmtpBlacklist("att.net",       AlertLevel.MEDIUM));
			lookups.add(new SmtpBlacklist("bellsouth.net", AlertLevel.MEDIUM));
			lookups.add(new SmtpBlacklist("comcast.net",   AlertLevel.MEDIUM));
			lookups.add(new SmtpBlacklist("pacbell.net",   AlertLevel.MEDIUM));
			lookups.add(new SmtpBlacklist("sbcglobal.net", AlertLevel.MEDIUM));
			lookups.add(new SmtpBlacklist("appriver.com",  AlertLevel.MEDIUM)); // List from trustsource.org
			// Note: When adding a new blacklist, change the + 6 in list constructor call
		}
		Collections.sort(lookups);
	}

	@Override
	protected int getColumns() {
		return 5;
	}

	@Override
	protected SerializableFunction<Locale, List<String>> getColumnHeaders() {
		return locale -> Arrays.asList(PACKAGE_RESOURCES.getMessage(locale, "BlacklistsNodeWorker.columnHeader.basename"),
			PACKAGE_RESOURCES.getMessage(locale, "BlacklistsNodeWorker.columnHeader.queryTime"),
			PACKAGE_RESOURCES.getMessage(locale, "BlacklistsNodeWorker.columnHeader.latency"),
			PACKAGE_RESOURCES.getMessage(locale, "BlacklistsNodeWorker.columnHeader.query"),
			PACKAGE_RESOURCES.getMessage(locale, "BlacklistsNodeWorker.columnHeader.result")
		);
	}

	private static final ExecutorService executorService = Executors.newFixedThreadPool(
		NUM_THREADS,
		(Runnable r) -> new Thread(r, BlacklistsNodeWorker.class.getName() + ".executorService")
	);

	private final Map<String, BlacklistQueryResult> queryResultCache = new HashMap<>();

	@Override
	@SuppressWarnings({"ThrowableResultIgnored", "UseSpecificCatch", "TooBroadCatch", "SleepWhileInLoop"})
	protected List<BlacklistQueryResult> getQueryResult() throws Exception {
		// Run each query in parallel
		List<Long> startTimes = new ArrayList<>(lookups.size());
		List<Long> startNanos = new ArrayList<>(lookups.size());
		List<Future<BlacklistQueryResult>> futures = new ArrayList<>(lookups.size());
		for(final BlacklistLookup lookup : lookups) {
			final String baseName = lookup.getBaseName();
			BlacklistQueryResult oldResult;
			synchronized(queryResultCache) {
				oldResult = queryResultCache.get(baseName);
			}
			final long currentTime = System.currentTimeMillis();
			boolean needNewQuery;
			if(oldResult==null) {
				needNewQuery = true;
			} else {
				long timeSince = currentTime - oldResult.queryTime;
				if(timeSince<0) timeSince = -timeSince; // Handle system time reset
				switch(oldResult.alertLevel) {
					case UNKNOWN:
						// Retry for those unknown
						needNewQuery = timeSince >= UNKNOWN_RETRY;
						break;
					case NONE:
						// Retry when no problem
						needNewQuery = timeSince >= GOOD_RETRY;
						break;
					default:
						// All others, retry
						needNewQuery = timeSince >= BAD_RETRY;
				}
			}
			if(needNewQuery) {
				startTimes.add(currentTime);
				startNanos.add(System.nanoTime());
				futures.add(
					executorService.submit(() -> {
						BlacklistQueryResult result = lookup.call();
						// Remember result even if timed-out on queue, this is to try to not lose any progress.
						// Time-outs are only cached here, never from a queue timeout
						synchronized(queryResultCache) {
							queryResultCache.put(baseName, result);
						}
						return result;
					})
				);
				Thread.sleep(TASK_DELAY);
			} else {
				startTimes.add(null);
				startNanos.add(null);
				futures.add(null);
			}
		}

		// Get the results
		List<BlacklistQueryResult> results = new ArrayList<>(lookups.size());
		for(int c=0;c<lookups.size();c++) {
			BlacklistLookup lookup = lookups.get(c);
			String baseName = lookup.getBaseName();
			BlacklistQueryResult result;
			Future<BlacklistQueryResult> future = futures.get(c);
			if(future==null) {
				// Use previously cached value
				synchronized(queryResultCache) {
					result = queryResultCache.get(baseName);
				}
				if(result==null) throw new AssertionError("result==null");
			} else {
				long startTime = startTimes.get(c);
				long startNano = startNanos.get(c);
				long timeoutRemainingNanos = startNano + TIMEOUT * 1000000L - System.nanoTime();
				if(timeoutRemainingNanos<0) timeoutRemainingNanos = 0L;
				boolean cacheResult;
				try {
					result = future.get(timeoutRemainingNanos, TimeUnit.NANOSECONDS);
					cacheResult = true;
				} catch(TimeoutException to) {
					future.cancel(false);
					result = new BlacklistQueryResult(baseName, startTime, System.nanoTime() - startNano, lookup.getQuery(), "Timeout in queue, timeoutRemaining = " + new NanoInterval(timeoutRemainingNanos), AlertLevel.UNKNOWN);
					// Queue timeouts are not cached
					cacheResult = false;
				} catch(ThreadDeath td) {
					try {
						future.cancel(false);
					} catch(Throwable t) {
						Throwable t2 = Throwables.addSuppressed(td, t);
						assert t2 == td;
					}
					throw td;
				} catch(Throwable t) {
					future.cancel(false);
					result = new BlacklistQueryResult(baseName, startTime, System.nanoTime() - startNano, lookup.getQuery(), t.getMessage(), lookup.getMaxAlertLevel());
					cacheResult = true;
					logger.log(Level.FINE, null, t); // TODO: Log all others that are put into result without full stack trace
					/*
					if(e instanceof InterruptedException) {
						// Restore the interrupted status
						Thread.currentThread().interrupt();
					}
					 */
				}
				if(cacheResult) {
					synchronized(queryResultCache) {
						queryResultCache.put(baseName, result);
					}
				}
			}
			results.add(result);
		}
		return results;
	}

	@Override
	protected SerializableFunction<Locale, List<Object>> getTableData(List<BlacklistQueryResult> queryResult) throws Exception {
		List<Object> tableData = new ArrayList<>(queryResult.size()*5);
		for(BlacklistQueryResult result : queryResult) {
			tableData.add(result.basename);
			tableData.add(new TimeWithTimeZone(result.queryTime));
			tableData.add(new NanoInterval(result.latency));
			tableData.add(result.query);
			tableData.add(result.result);
		}
		return locale -> tableData;
	}

	@Override
	protected List<AlertLevel> getAlertLevels(List<BlacklistQueryResult> queryResult) {
		List<AlertLevel> alertLevels = new ArrayList<>(queryResult.size());
		for(BlacklistQueryResult result : queryResult) {
			alertLevels.add(result.alertLevel);
		}
		return alertLevels;
	}

	@Override
	public AlertLevelAndMessage getAlertLevelAndMessage(AlertLevel curAlertLevel, TableResult result) {
		AlertLevel highestAlertLevel = AlertLevel.NONE;
		Function<Locale, String> highestAlertMessage = null;
		if(result.isError()) {
			highestAlertLevel = result.getAlertLevels().get(0);
			highestAlertMessage = locale -> result.getTableData(locale).get(0).toString();
		} else {
			List<?> tableData = result.getTableData(Locale.getDefault());
			for(int index=0,len=tableData.size();index<len;index+=5) {
				AlertLevel alertLevel = result.getAlertLevels().get(index/5);
				// Too many queries time-out: if alert level is "Unknown", treat as "Low"
				if(alertLevel == AlertLevel.UNKNOWN) alertLevel = AlertLevel.LOW;
				if(alertLevel.compareTo(highestAlertLevel)>0) {
					highestAlertLevel = alertLevel;
					Object resultBasename = tableData.get(index);
					Object resultValue = tableData.get(index+4);
					highestAlertMessage = locale -> resultBasename+": "+(resultValue==null ? "null" : resultValue.toString());
				}
			}
		}
		// Do not allow higher than MEDIUM, even if individual rows are higher
		// if(highestAlertLevel.compareTo(AlertLevel.MEDIUM)>0) highestAlertLevel=AlertLevel.MEDIUM;
		return new AlertLevelAndMessage(highestAlertLevel, highestAlertMessage);
	}

	/**
	 * The sleep delay is always 15 minutes.  The query results are cached and will
	 * be reused until individual timeouts.
	 */
	@Override
	protected long getSleepDelay(boolean lastSuccessful, AlertLevel alertLevel) {
		return 15L * 60L * 1000L;
	}

	@Override
	protected long getTimeout() {
		return TIMEOUT + 5L*60L*1000L; // Allow extra five minutes to try to capture timeouts on a row-by-row basis.
	}

	@Override
	protected TimeUnit getTimeoutUnit() {
		return TimeUnit.MILLISECONDS;
	}

	/**
	 * The startup delay is within fifteen minutes.
	 */
	@Override
	protected int getNextStartupDelay() {
		return RootNodeImpl.getNextStartupDelayFifteenMinutes();
	}
}
