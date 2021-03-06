package org.briarproject.plugins.file;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

class PollingRemovableDriveMonitor implements RemovableDriveMonitor, Runnable {

	private static final Logger LOG =
			Logger.getLogger(PollingRemovableDriveMonitor.class.getName());

	private final Executor ioExecutor;
	private final RemovableDriveFinder finder;
	private final int pollingInterval;

	private final Lock pollingLock = new ReentrantLock();
	private final Condition stopPolling = pollingLock.newCondition();

	private volatile boolean running = false;
	private volatile Callback callback = null;

	public PollingRemovableDriveMonitor(Executor ioExecutor,
			RemovableDriveFinder finder, int pollingInterval) {
		this.ioExecutor = ioExecutor;
		this.finder = finder;
		this.pollingInterval = pollingInterval;
	}

	public void start(Callback callback) throws IOException {
		this.callback = callback;
		running = true;
		ioExecutor.execute(this);
	}

	public void stop() throws IOException {
		running = false;
		pollingLock.lock();
		try {
			stopPolling.signalAll();
		}
		finally {
			pollingLock.unlock();
		}
	}

	public void run() {
		try {
			Collection<File> drives = finder.findRemovableDrives();
			while(running) {
				pollingLock.lock();
				try {
					stopPolling.await(pollingInterval, MILLISECONDS);
				} finally {
					pollingLock.unlock();
				}
				if(!running) return;
				Collection<File> newDrives = finder.findRemovableDrives();
				for(File f : newDrives) {
					if(!drives.contains(f)) callback.driveInserted(f);
				}
				drives = newDrives;
			}
		} catch(InterruptedException e) {
			LOG.warning("Interrupted while waiting to poll");
			Thread.currentThread().interrupt();
		} catch(IOException e) {
			callback.exceptionThrown(e);
		}
	}
}
