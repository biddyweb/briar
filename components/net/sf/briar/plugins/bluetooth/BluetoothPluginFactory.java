package net.sf.briar.plugins.bluetooth;

import java.util.concurrent.Executor;

import net.sf.briar.api.plugins.StreamPluginCallback;
import net.sf.briar.api.plugins.StreamPlugin;
import net.sf.briar.api.plugins.StreamPluginFactory;

public class BluetoothPluginFactory implements StreamPluginFactory {

	private static final long POLLING_INTERVAL = 3L * 60L * 1000L; // 3 mins

	public StreamPlugin createPlugin(Executor executor,
			StreamPluginCallback callback) {
		return new BluetoothPlugin(executor, callback, POLLING_INTERVAL);
	}
}