/*
 * Copyright 2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.noc.monitor;

import com.aoindustries.util.ErrorHandler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Writes files in the background.  The files are written in the order received.
 * If a new version of the file is provided while the old version has not yet been
 * written, the new version will take its place in the queue, keeping the position of the older item in the queue.
 * The expected result is that different files will get written in a round-robin style
 * while keeping the most up-to-date copies during times of heave disk I/O.
 *
 * This is being used to get around an issue where the monitoring would cause extremely
 * high load when running on a very busy disk subsystem.  The resulting load would
 * causing things to get progressively worse.
 *
 * TODO: Should we perform any sort of batching?  Write all built-up in a minute at once?  Save HDD disk I/O?
 *       Or more extreme?  Only once per very long time period on the laptop?  Or, when it finds the disk already spun-up
 *       for some other reason?  Been using /dev/shm to avoid night-time disk I/O to keep it quiet while sleeping, but
 *       this loses all info on a reboot.  Is a pen drive a better solution?
 *
 * TODO: We could also add a map for finding the existing queue entry, to avoid the sequential scan.  This would use less CPU when the disks are being extremely slow.
 *
 * @author  AO Industries, Inc.
 */
class BackgroundWriter {

    private static boolean DEBUG = false;

    private static class PathAndData {

        final private File persistenceFile;
        private File newPersistenceFile;
        private ErrorHandler errorHandler;
        private byte[] data;

        private PathAndData(File persistenceFile, File newPersistenceFile, ErrorHandler errorHandler, byte[] data) {
            this.persistenceFile = persistenceFile;
            this.newPersistenceFile = newPersistenceFile;
            this.errorHandler = errorHandler;
            this.data = data;
        }
    }

    // These are both synchronized on queue
    private static final LinkedList<PathAndData> queue = new LinkedList<PathAndData>();
    private static boolean running = false;

    /**
     * Will serialize and queue the object for write.
     */
    static void enqueueObject(File persistenceFile, File newPersistenceFile, ErrorHandler errorHandler, Serializable serializable, boolean gzip) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oout = new ObjectOutputStream(gzip ? new GZIPOutputStream(bout) : bout);
        oout.writeObject(serializable);
        oout.close();
        enqueueFile(persistenceFile, newPersistenceFile, errorHandler, bout.toByteArray());
    }

    /**
     * Queues the file for write.  The data is not copied to a temporary array - do not change after giving to this method.
     */
    static void enqueueFile(File persistenceFile, File newPersistenceFile, ErrorHandler errorHandler, byte[] data) {
        synchronized(queue) {
            // Scan the queue for the same persistenceFile
            boolean found = false;
            for(PathAndData pad : queue) {
                if(pad.persistenceFile.equals(persistenceFile)) {
                    if(DEBUG) System.out.println("DEBUG: BackgroundWriter: Updating existing in queue");
                    pad.newPersistenceFile = newPersistenceFile;
                    pad.errorHandler = errorHandler;
                    pad.data = data;
                    found = true;
                }
            }
            if(!found) {
                queue.addLast(new PathAndData(persistenceFile, newPersistenceFile, errorHandler, data));
            }
            if(!running) {
                RootNodeImpl.executorService.submit(
                    new Runnable() {
                        @Override
                        public void run() {
                            int counter = 0;
                            while(true) {
                                // Get the next file from the queue until done
                                PathAndData pad;
                                synchronized(queue) {
                                    if(queue.isEmpty()) {
                                        running = false;
                                        if(DEBUG) System.out.println("DEBUG: BackgroundWriter: Total burst from queue: "+counter);
                                        return;
                                    }
                                    pad = queue.removeFirst();
                                    counter++;
                                }
                                try {
                                    OutputStream out = new FileOutputStream(pad.newPersistenceFile);
                                    try {
                                        out.write(pad.data);
                                    } finally {
                                        out.close();
                                    }
                                    // Try move over first (Linux)
                                    if(!pad.newPersistenceFile.renameTo(pad.persistenceFile)) {
                                        // Delete and rename (Windows)
                                        if(!pad.persistenceFile.delete()) {
                                            throw new IOException(
                                                ApplicationResourcesAccessor.getMessage(
                                                    Locale.getDefault(),
                                                    "BackgroundWriter.error.unableToDelete",
                                                    pad.persistenceFile.getCanonicalPath()
                                                )
                                            );
                                        }
                                        if(!pad.newPersistenceFile.renameTo(pad.persistenceFile)) {
                                            throw new IOException(
                                                ApplicationResourcesAccessor.getMessage(
                                                    Locale.getDefault(),
                                                    "BackgroundWriter.error.unableToRename",
                                                    pad.newPersistenceFile.getCanonicalPath(),
                                                    pad.persistenceFile.getCanonicalPath()
                                                )
                                            );
                                        }
                                    }
                                } catch(Exception err) {
                                    pad.errorHandler.reportError(err, null);
                                }
                            }
                        }
                    }
                );
                running = true;
            }
        }
    }
}
