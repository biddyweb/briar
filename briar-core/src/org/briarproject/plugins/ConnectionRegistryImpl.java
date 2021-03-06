package org.briarproject.plugins;

import static java.util.logging.Level.INFO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.briarproject.api.ContactId;
import org.briarproject.api.TransportId;
import org.briarproject.api.event.ContactConnectedEvent;
import org.briarproject.api.event.ContactDisconnectedEvent;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.plugins.ConnectionRegistry;

import com.google.inject.Inject;

class ConnectionRegistryImpl implements ConnectionRegistry {

	private static final Logger LOG =
			Logger.getLogger(ConnectionRegistryImpl.class.getName());

	private final EventBus eventBus;
	private final Lock synchLock = new ReentrantLock();

	// The following are locking: synchLock
	private final Map<TransportId, Map<ContactId, Integer>> connections;
	private final Map<ContactId, Integer> contactCounts;

	@Inject
	ConnectionRegistryImpl(EventBus eventBus) {
		this.eventBus = eventBus;
		connections = new HashMap<TransportId, Map<ContactId, Integer>>();
		contactCounts = new HashMap<ContactId, Integer>();
	}

	public void registerConnection(ContactId c, TransportId t) {
		LOG.info("Connection registered");
		boolean firstConnection = false;
		synchLock.lock();
		try {
			Map<ContactId, Integer> m = connections.get(t);
			if(m == null) {
				m = new HashMap<ContactId, Integer>();
				connections.put(t, m);
			}
			Integer count = m.get(c);
			if(count == null) m.put(c, 1);
			else m.put(c, count + 1);
			count = contactCounts.get(c);
			if(count == null) {
				firstConnection = true;
				contactCounts.put(c, 1);
			} else {
				contactCounts.put(c, count + 1);
			}
		} finally {
			synchLock.unlock();
		}

		if(firstConnection) {
			LOG.info("Contact connected");
			eventBus.broadcast(new ContactConnectedEvent(c));
		}
	}

	public void unregisterConnection(ContactId c, TransportId t) {
		LOG.info("Connection unregistered");
		boolean lastConnection = false;
		synchLock.lock();
		try {
			Map<ContactId, Integer> m = connections.get(t);
			if(m == null) throw new IllegalArgumentException();
			Integer count = m.remove(c);
			if(count == null) throw new IllegalArgumentException();
			if(count == 1) {
				if(m.isEmpty()) connections.remove(t);
			} else {
				m.put(c, count - 1);
			}
			count = contactCounts.get(c);
			if(count == null) throw new IllegalArgumentException();
			if(count == 1) {
				lastConnection = true;
				contactCounts.remove(c);
			} else {
				contactCounts.put(c, count - 1);
			}
		} finally {
			synchLock.unlock();
		}

		if(lastConnection) {
			LOG.info("Contact disconnected");
			eventBus.broadcast(new ContactDisconnectedEvent(c));
		}
	}

	public Collection<ContactId> getConnectedContacts(
			TransportId t) {
		synchLock.lock();
		try {
			Map<ContactId, Integer> m = connections.get(t);
			if(m == null) return Collections.emptyList();
			List<ContactId> ids = new ArrayList<ContactId>(m.keySet());
			if(LOG.isLoggable(INFO)) LOG.info(ids.size() + " contacts connected");
			return Collections.unmodifiableList(ids);
		} finally {
			synchLock.unlock();
		}

	}

	public boolean isConnected(ContactId c) {
		synchLock.lock();
		try {
			return contactCounts.containsKey(c);
		} finally {
			synchLock.unlock();
		}

	}
}
