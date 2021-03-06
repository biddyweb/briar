package org.briarproject.plugins.tcp;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.briarproject.api.lifecycle.ShutdownManager;
import org.xml.sax.SAXException;

class PortMapperImpl implements PortMapper {

	private static final Logger LOG =
			Logger.getLogger(PortMapperImpl.class.getName());

	private final ShutdownManager shutdownManager;
	private final AtomicBoolean started = new AtomicBoolean(false);

	private volatile GatewayDevice gateway = null;

	PortMapperImpl(ShutdownManager shutdownManager) {
		this.shutdownManager = shutdownManager;
	}

	public MappingResult map(final int port) {
		if(!started.getAndSet(true)) start();
		if(gateway == null) return null;
		InetAddress internal = gateway.getLocalAddress();
		if(internal == null) return null;
		if(LOG.isLoggable(INFO))
			LOG.info("Internal address " + getHostAddress(internal));
		boolean succeeded = false;
		InetAddress external = null;
		try {
			succeeded = gateway.addPortMapping(port, port,
					getHostAddress(internal), "TCP", "TCP");
			if(succeeded) {
				shutdownManager.addShutdownHook(new Runnable() {
					public void run() {
						deleteMapping(port);
					}
				});
			}
			String externalString = gateway.getExternalIPAddress();
			if(LOG.isLoggable(INFO))
				LOG.info("External address " + externalString);
			if(externalString != null)
				external = InetAddress.getByName(externalString);
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		} catch(SAXException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
		return new MappingResult(internal, external, port, succeeded);
	}

	private String getHostAddress(InetAddress a) {
		String addr = a.getHostAddress();
		int percent = addr.indexOf('%');
		if(percent == -1) return addr;
		return addr.substring(0, percent);
	}

	private void start() {
		GatewayDiscover d = new GatewayDiscover();
		try {
			d.discover();
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		} catch(SAXException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		} catch(ParserConfigurationException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
		gateway = d.getValidGateway();
	}

	private void deleteMapping(int port) {
		try {
			gateway.deletePortMapping(port, "TCP");
			if(LOG.isLoggable(INFO))
				LOG.info("Deleted mapping for port " + port); 
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		} catch(SAXException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
	}
}
