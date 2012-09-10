/*
 * Copyright 2008-2012 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.MonitoringPoint;
import com.aoindustries.noc.monitor.common.SingleResult;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The workers for single results node.
 *
 * TODO: Add persistence of the last report
 *
 * @author  AO Industries, Inc.
 */
abstract class SingleResultNodeWorker implements Runnable {

    private static final Logger logger = Logger.getLogger(SingleResultNodeWorker.class.getName());

    final protected MonitoringPoint monitoringPoint;

    /**
     * The most recent timer task
     */
    private final Object timerTaskLock = new Object();
    private Future<?> timerTask;

    volatile private SingleResult lastResult;
    volatile private AlertLevel alertLevel = AlertLevel.UNKNOWN;
    volatile private String alertMessage = null;

    final private List<SingleResultNodeImpl> singleResultNodeImpls = new ArrayList<SingleResultNodeImpl>();

    final protected File persistenceFile;

    SingleResultNodeWorker(MonitoringPoint monitoringPoint, File persistenceFile) {
        this.monitoringPoint = monitoringPoint;
        this.persistenceFile = persistenceFile;
    }

    final SingleResult getLastResult() {
        return lastResult;
    }

    final AlertLevel getAlertLevel() {
        return alertLevel;
    }
    
    final String getAlertMessage() {
        return alertMessage;
    }

    /**
     * The default startup delay is within five minutes.
     */
    protected int getNextStartupDelay() {
        return RootNodeImpl.getNextStartupDelayFiveMinutes();
    }

    private void start() {
        synchronized(timerTaskLock) {
            assert timerTask==null : "thread already started";
            timerTask = RootNodeImpl.schedule(this, getNextStartupDelay());
        }
    }

    private void stop() {
        synchronized(timerTaskLock) {
            if(timerTask!=null) {
                timerTask.cancel(true);
                timerTask = null;
            }
        }
    }

    private String getReportWithTimeout() throws Exception {
        Future<String> future = RootNodeImpl.executorService.submitUnbounded(
            new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return getReport();
                }
            }
        );
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch(InterruptedException err) {
            cancel(future);
            throw err;
        } catch(TimeoutException err) {
            cancel(future);
            throw err;
        }
    }

    @Override
    final public void run() {
        boolean lastSuccessful = false;
        synchronized(timerTaskLock) {if(timerTask==null) return;}
        try {
            long startMillis = System.currentTimeMillis();
            long startNanos = System.nanoTime();

            lastSuccessful = false;

            String error;
            String report;
            try {
                error = null;
                report = getReportWithTimeout();
                if(report==null) throw new NullPointerException("report is null");
                lastSuccessful = true;
            } catch(Exception err) {
                error = err.getLocalizedMessage();
                if(error==null) error = err.toString();
                report = null;
                lastSuccessful = false;
            }
            long pingNanos = System.nanoTime() - startNanos;

            synchronized(timerTaskLock) {if(timerTask==null) return;}

            SingleResult result = new SingleResult(
                monitoringPoint,
                startMillis,
                pingNanos,
                error,
                report
            );
            lastResult = result;

            AlertLevel curAlertLevel = alertLevel;
            if(curAlertLevel==AlertLevel.UNKNOWN) curAlertLevel = AlertLevel.NONE;
            AlertLevelAndMessage alertLevelAndMessage = getAlertLevelAndMessage(Locale.getDefault(), result);
            AlertLevel maxAlertLevel = alertLevelAndMessage.getAlertLevel();
            AlertLevel newAlertLevel;
            if(maxAlertLevel.compareTo(curAlertLevel)<0) {
                // If maxAlertLevel < current, drop current to be the max
                newAlertLevel = maxAlertLevel;
            } else if(curAlertLevel.compareTo(maxAlertLevel)<0) {
                // If current < maxAlertLevel, increment by one
                newAlertLevel = AlertLevel.values()[curAlertLevel.ordinal()+1];
            } else {
                newAlertLevel = maxAlertLevel;
            }

            AlertLevel oldAlertLevel = alertLevel;
            alertLevel = newAlertLevel;
            alertMessage = alertLevelAndMessage.getAlertMessage();

            singleResultUpdated(result);
            if(oldAlertLevel!=newAlertLevel) {
                synchronized(singleResultNodeImpls) {
                    for(SingleResultNodeImpl singleResultNodeImpl : singleResultNodeImpls) {
                        singleResultNodeImpl.nodeAlertLevelChanged(
                            oldAlertLevel,
                            newAlertLevel,
                            result
                        );
                    }
                }
            }
        } catch(Exception err) {
            logger.log(Level.SEVERE, null, err);
            lastSuccessful = false;
        } finally {
            // Reschedule next timer task if still running
            synchronized(timerTaskLock) {
                if(timerTask!=null) {
                    timerTask = RootNodeImpl.schedule(
                        this,
                        getSleepDelay(lastSuccessful, alertLevel)
                    );
                }
            }
        }
    }

    final void addSingleResultNodeImpl(SingleResultNodeImpl singleResultNodeImpl) {
        synchronized(singleResultNodeImpls) {
            boolean needsStart = singleResultNodeImpls.isEmpty();
            singleResultNodeImpls.add(singleResultNodeImpl);
            if(needsStart) start();
        }
    }

    final void removeSingleResultNodeImpl(SingleResultNodeImpl singleResultNodeImpl) {
        // TODO: log error if wrong number of listeners matched
        synchronized(singleResultNodeImpls) {
            if(singleResultNodeImpls.isEmpty()) throw new AssertionError("singleResultNodeImpls is empty");
            for(int c=singleResultNodeImpls.size()-1;c>=0;c--) {
                if(singleResultNodeImpls.get(c)==singleResultNodeImpl) {
                    singleResultNodeImpls.remove(c);
                    break;
                }
            }
            if(singleResultNodeImpls.isEmpty()) {
                stop();
            }
        }
    }

    /**
     * Notifies all of the listeners.
     */
    private void singleResultUpdated(SingleResult singleResult) {
        synchronized(singleResultNodeImpls) {
            for(SingleResultNodeImpl singleResultNodeImpl : singleResultNodeImpls) {
                singleResultNodeImpl.singleResultUpdated(singleResult);
            }
        }
    }

    /**
     * The default sleep delay is five minutes when successful
     * or one minute when unsuccessful.
     */
    protected long getSleepDelay(boolean lastSuccessful, AlertLevel alertLevel) {
        return lastSuccessful && alertLevel==AlertLevel.NONE ? 5*60000 : 60000;
    }

    /**
     * Determines the alert level and message for the provided result and locale.
     */
    protected abstract AlertLevelAndMessage getAlertLevelAndMessage(Locale locale, SingleResult result);

    /**
     * Gets the report for this worker.
     */
    protected abstract String getReport() throws Exception;

    /**
     * Cancels the current getReport call on a best-effort basis.
     * Implementations of this method <b>must not block</b>.
     * This default implementation calls <code>future.cancel(true)</code>.
     */
    protected void cancel(Future<String> future) {
        future.cancel(true);
    }
}
