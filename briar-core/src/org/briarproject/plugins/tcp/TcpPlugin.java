package org.briarproject.plugins.tcp;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.briarproject.api.ContactId;
import org.briarproject.api.TransportProperties;
import org.briarproject.api.crypto.PseudoRandom;
import org.briarproject.api.plugins.duplex.DuplexPlugin;
import org.briarproject.api.plugins.duplex.DuplexPluginCallback;
import org.briarproject.api.plugins.duplex.DuplexTransportConnection;
import org.briarproject.util.StringUtils;

abstract class TcpPlugin implements DuplexPlugin {

	private static final Pattern DOTTED_QUAD =
			Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
	private static final Logger LOG =
			Logger.getLogger(TcpPlugin.class.getName());

	protected final Executor ioExecutor;
	protected final DuplexPluginCallback callback;
	protected final int maxLatency, maxIdleTime, pollingInterval, socketTimeout;

	protected volatile boolean running = false;
	protected volatile ServerSocket socket = null;

	/**
	 * Returns zero or more socket addresses on which the plugin should listen,
	 * in order of preference. At most one of the addresses will be bound.
	 */
	protected abstract List<SocketAddress> getLocalSocketAddresses();

	/** Returns true if connections to the given address can be attempted. */
	protected abstract boolean isConnectable(InetSocketAddress remote);

	protected TcpPlugin(Executor ioExecutor, DuplexPluginCallback callback,
			int maxLatency, int maxIdleTime, int pollingInterval) {
		this.ioExecutor = ioExecutor;
		this.callback = callback;
		this.maxLatency = maxLatency;
		this.maxIdleTime = maxIdleTime;
		this.pollingInterval = pollingInterval;
		if(maxIdleTime > Integer.MAX_VALUE / 2)
			socketTimeout = Integer.MAX_VALUE;
		else socketTimeout = maxIdleTime * 2;
	}

	public int getMaxLatency() {
		return maxLatency;
	}

	public int getMaxIdleTime() {
		return maxIdleTime;
	}

	public boolean start() {
		running = true;
		bind();
		return true;
	}

	protected void bind() {
		ioExecutor.execute(new Runnable() {
			public void run() {
				if(!running) return;
				ServerSocket ss = null;
				for(SocketAddress addr : getLocalSocketAddresses()) {
					try {
						ss = new ServerSocket();
						ss.bind(addr);
						break;
					} catch(IOException e) {
						if(LOG.isLoggable(INFO))
							LOG.info("Failed to bind " + addr);
						tryToClose(ss);
						continue;
					}
				}
				if(ss == null || !ss.isBound()) {
					LOG.info("Could not bind server socket");
					return;
				}
				if(!running) {
					tryToClose(ss);
					return;
				}
				socket = ss;
				SocketAddress local = ss.getLocalSocketAddress();
				setLocalSocketAddress((InetSocketAddress) local);
				if(LOG.isLoggable(INFO)) LOG.info("Listening on " + local);
				callback.pollNow();
				acceptContactConnections();
			}
		});
	}

	protected void tryToClose(ServerSocket ss) {
		try {
			if(ss != null) ss.close();
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
		}
	}

	protected String getHostAddress(InetAddress a) {
		String addr = a.getHostAddress();
		int percent = addr.indexOf('%');
		return percent == -1 ? addr : addr.substring(0, percent);
	}

	protected void setLocalSocketAddress(InetSocketAddress a) {
		TransportProperties p = new TransportProperties();
		p.put("address", getHostAddress(a.getAddress()));
		p.put("port", String.valueOf(a.getPort()));
		callback.mergeLocalProperties(p);
	}

	private void acceptContactConnections() {
		while(isRunning()) {
			Socket s;
			try {
				s = socket.accept();
				s.setSoTimeout(socketTimeout);
			} catch(IOException e) {
				// This is expected when the socket is closed
				if(LOG.isLoggable(INFO)) LOG.info(e.toString());
				return;
			}
			if(LOG.isLoggable(INFO))
				LOG.info("Connection from " + s.getRemoteSocketAddress());
			TcpTransportConnection conn = new TcpTransportConnection(this, s);
			callback.incomingConnectionCreated(conn);
		}
	}

	public void stop() {
		running = false;
		tryToClose(socket);
	}

	public boolean isRunning() {
		return running && socket != null && !socket.isClosed();
	}

	public boolean shouldPoll() {
		return true;
	}

	public int getPollingInterval() {
		return pollingInterval;
	}

	public void poll(Collection<ContactId> connected) {
		if(!isRunning()) return;
		for(ContactId c : callback.getRemoteProperties().keySet())
			if(!connected.contains(c)) connectAndCallBack(c);
	}

	private void connectAndCallBack(final ContactId c) {
		ioExecutor.execute(new Runnable() {
			public void run() {
				DuplexTransportConnection d = createConnection(c);
				if(d != null) callback.outgoingConnectionCreated(c, d);
			}
		});
	}

	public DuplexTransportConnection createConnection(ContactId c) {
		if(!isRunning()) return null;
		InetSocketAddress remote = getRemoteSocketAddress(c);
		if(remote == null) return null;
		if(!isConnectable(remote)) {
			if(LOG.isLoggable(INFO)) {
				SocketAddress local = socket.getLocalSocketAddress();
				LOG.info(remote + " is not connectable from " + local);
			}
			return null;
		}
		Socket s = new Socket();
		try {
			if(LOG.isLoggable(INFO)) LOG.info("Connecting to " + remote);
			s.connect(remote);
			s.setSoTimeout(socketTimeout);
			if(LOG.isLoggable(INFO)) LOG.info("Connected to " + remote);
			return new TcpTransportConnection(this, s);
		} catch(IOException e) {
			if(LOG.isLoggable(INFO)) LOG.info("Could not connect to " + remote);
			return null;
		}
	}

	private InetSocketAddress getRemoteSocketAddress(ContactId c) {
		TransportProperties p = callback.getRemoteProperties().get(c);
		if(p == null) return null;
		return parseSocketAddress(p.get("address"), p.get("port"));
	}

	protected InetSocketAddress parseSocketAddress(String addr, String port) {
		if(StringUtils.isNullOrEmpty(addr)) return null;
		if(StringUtils.isNullOrEmpty(port)) return null;
		// Ensure getByName() won't perform a DNS lookup
		if(!DOTTED_QUAD.matcher(addr).matches()) return null;
		try {
			InetAddress a = InetAddress.getByName(addr);
			int p = Integer.parseInt(port);
			return new InetSocketAddress(a, p);
		} catch(UnknownHostException e) {
			if(LOG.isLoggable(WARNING)) LOG.warning("Invalid address: " + addr);
			return null;
		} catch(NumberFormatException e) {
			if(LOG.isLoggable(WARNING)) LOG.warning("Invalid port: " + port);
			return null;
		}
	}

	public boolean supportsInvitations() {
		return false;
	}

	public DuplexTransportConnection createInvitationConnection(PseudoRandom r,
			long timeout) {
		throw new UnsupportedOperationException();
	}

	protected Collection<InetAddress> getLocalIpAddresses() {
		List<NetworkInterface> ifaces;
		try {
			ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
		} catch(SocketException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			return Collections.emptyList();
		}
		List<InetAddress> addrs = new ArrayList<InetAddress>();
		for(NetworkInterface iface : ifaces)
			addrs.addAll(Collections.list(iface.getInetAddresses()));
		return addrs;
	}
}
