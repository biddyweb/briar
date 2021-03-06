package org.briarproject.plugins.droidtooth;

import java.security.SecureRandom;
import java.util.concurrent.Executor;

import org.briarproject.api.TransportId;
import org.briarproject.api.android.AndroidExecutor;
import org.briarproject.api.plugins.duplex.DuplexPlugin;
import org.briarproject.api.plugins.duplex.DuplexPluginCallback;
import org.briarproject.api.plugins.duplex.DuplexPluginFactory;
import org.briarproject.api.system.Clock;
import org.briarproject.system.SystemClock;

import android.content.Context;

public class DroidtoothPluginFactory implements DuplexPluginFactory {

	private static final int MAX_LATENCY = 30 * 1000; // 30 seconds
	private static final int POLLING_INTERVAL = 3 * 60 * 1000; // 3 minutes

	private final Executor ioExecutor;
	private final AndroidExecutor androidExecutor;
	private final Context appContext;
	private final SecureRandom secureRandom;
	private final Clock clock;

	public DroidtoothPluginFactory(Executor ioExecutor,
			AndroidExecutor androidExecutor, Context appContext,
			SecureRandom secureRandom) {
		this.ioExecutor = ioExecutor;
		this.androidExecutor = androidExecutor;
		this.appContext = appContext;
		this.secureRandom = secureRandom;
		clock = new SystemClock();
	}

	public TransportId getId() {
		return DroidtoothPlugin.ID;
	}

	public DuplexPlugin createPlugin(DuplexPluginCallback callback) {
		return new DroidtoothPlugin(ioExecutor, androidExecutor, appContext,
				secureRandom, clock, callback, MAX_LATENCY, POLLING_INTERVAL);
	}
}
