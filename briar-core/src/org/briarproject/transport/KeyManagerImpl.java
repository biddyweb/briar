package org.briarproject.transport;

import static java.util.logging.Level.WARNING;
import static org.briarproject.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimerTask;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.briarproject.api.ContactId;
import org.briarproject.api.TransportId;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.KeyManager;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.event.ContactRemovedEvent;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.TransportAddedEvent;
import org.briarproject.api.event.TransportRemovedEvent;
import org.briarproject.api.system.Clock;
import org.briarproject.api.system.Timer;
import org.briarproject.api.transport.Endpoint;
import org.briarproject.api.transport.StreamContext;
import org.briarproject.api.transport.TagRecogniser;
import org.briarproject.api.transport.TemporarySecret;

// FIXME: Don't make alien calls with a lock held
class KeyManagerImpl extends TimerTask implements KeyManager, EventListener {

	private static final int MS_BETWEEN_CHECKS = 60 * 1000;

	private static final Logger LOG =
			Logger.getLogger(KeyManagerImpl.class.getName());

	private final CryptoComponent crypto;
	private final DatabaseComponent db;
	private final EventBus eventBus;
	private final TagRecogniser tagRecogniser;
	private final Clock clock;
	private final Timer timer;
	private final Lock synchLock = new ReentrantLock();

	// The following are locking: synchLock
	private final Map<TransportId, Integer> maxLatencies;
	private final Map<EndpointKey, TemporarySecret> oldSecrets;
	private final Map<EndpointKey, TemporarySecret> currentSecrets;
	private final Map<EndpointKey, TemporarySecret> newSecrets;

	@Inject
	KeyManagerImpl(CryptoComponent crypto, DatabaseComponent db,
			EventBus eventBus, TagRecogniser tagRecogniser, Clock clock,
			Timer timer) {
		this.crypto = crypto;
		this.db = db;
		this.eventBus = eventBus;
		this.tagRecogniser = tagRecogniser;
		this.clock = clock;
		this.timer = timer;
		maxLatencies = new HashMap<TransportId, Integer>();
		oldSecrets = new HashMap<EndpointKey, TemporarySecret>();
		currentSecrets = new HashMap<EndpointKey, TemporarySecret>();
		newSecrets = new HashMap<EndpointKey, TemporarySecret>();
	}

	public boolean start() {
		synchLock.lock();
		try {
			eventBus.addListener(this);
			// Load the temporary secrets and transport latencies from the DB
			Collection<TemporarySecret> secrets;
			try {
				secrets = db.getSecrets();
				maxLatencies.putAll(db.getTransportLatencies());
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				return false;
			}
			// Work out what phase of its lifecycle each secret is in
			long now = clock.currentTimeMillis();
			Collection<TemporarySecret> dead =
					assignSecretsToMaps(now, secrets);
			// Replace any dead secrets
			Collection<TemporarySecret> created = replaceDeadSecrets(now, dead);
			if(!created.isEmpty()) {
				// Store any secrets that have been created,
				// removing any dead ones
				try {
					db.addSecrets(created);
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
					return false;
				}
			}
			// Pass the old, current and new secrets to the recogniser
			for(TemporarySecret s : oldSecrets.values())
				tagRecogniser.addSecret(s);
			for(TemporarySecret s : currentSecrets.values())
				tagRecogniser.addSecret(s);
			for(TemporarySecret s : newSecrets.values())
				tagRecogniser.addSecret(s);
			// Schedule periodic key rotation
			timer.scheduleAtFixedRate(this, MS_BETWEEN_CHECKS,
					MS_BETWEEN_CHECKS);
			return true;
		} finally {
			synchLock.unlock();
		}
	}

	// Assigns secrets to the appropriate maps and returns any dead secrets
	// Locking: synchLock
	private Collection<TemporarySecret> assignSecretsToMaps(long now,
			Collection<TemporarySecret> secrets) {
		Collection<TemporarySecret> dead = new ArrayList<TemporarySecret>();
		for(TemporarySecret s : secrets) {
			// Discard the secret if the transport has been removed
			Integer maxLatency = maxLatencies.get(s.getTransportId());
			if(maxLatency == null) {
				LOG.info("Discarding obsolete secret");
				continue;
			}
			long rotation = maxLatency + MAX_CLOCK_DIFFERENCE;
			long creationTime = s.getEpoch() + rotation * (s.getPeriod() - 2);
			long activationTime = creationTime + rotation;
			long deactivationTime = activationTime + rotation;
			long destructionTime = deactivationTime + rotation;
			if(now >= destructionTime) {
				dead.add(s);
			} else if(now >= deactivationTime) {
				oldSecrets.put(new EndpointKey(s), s);
			} else if(now >= activationTime) {
				currentSecrets.put(new EndpointKey(s), s);
			} else if(now >= creationTime) {
				newSecrets.put(new EndpointKey(s), s);
			} else {
				// FIXME: Work out what to do here
				throw new Error("Clock has moved backwards");
			}
		}
		return dead;
	}

	// Replaces the given secrets and returns any secrets created
	// Locking: synchLock
	private Collection<TemporarySecret> replaceDeadSecrets(long now,
			Collection<TemporarySecret> dead) {
		// If there are several dead secrets for an endpoint, use the newest
		Map<EndpointKey, TemporarySecret> newest =
				new HashMap<EndpointKey, TemporarySecret>();
		for(TemporarySecret s : dead) {
			EndpointKey k = new EndpointKey(s);
			TemporarySecret exists = newest.get(k);
			if(exists == null) {
				// There's no other secret for this endpoint
				newest.put(k, s);
			} else if(exists.getPeriod() < s.getPeriod()) {
				// There's an older secret - use this one instead
				newest.put(k, s);
			} else {
				// There's a newer secret - keep using it
			}
		}
		Collection<TemporarySecret> created = new ArrayList<TemporarySecret>();
		for(Entry<EndpointKey, TemporarySecret> e : newest.entrySet()) {
			TemporarySecret s = e.getValue();
			Integer maxLatency = maxLatencies.get(s.getTransportId());
			if(maxLatency == null) throw new IllegalStateException();
			// Work out which rotation period we're in
			long elapsed = now - s.getEpoch();
			long rotation = maxLatency + MAX_CLOCK_DIFFERENCE;
			long period = (elapsed / rotation) + 1;
			if(period < 1) throw new IllegalStateException();
			if(period - s.getPeriod() < 2)
				throw new IllegalStateException();
			// Derive the old, current and new secrets
			byte[] b1 = s.getSecret();
			for(long p = s.getPeriod() + 1; p < period; p++)
				b1 = crypto.deriveNextSecret(b1, p);
			byte[] b2 = crypto.deriveNextSecret(b1, period);
			byte[] b3 = crypto.deriveNextSecret(b2, period + 1);
			// Add the secrets to their respective maps if not already present
			EndpointKey k = e.getKey();
			if(!oldSecrets.containsKey(k)) {
				TemporarySecret s1 = new TemporarySecret(s, period - 1, b1);
				oldSecrets.put(k, s1);
				created.add(s1);
			}
			if(!currentSecrets.containsKey(k)) {
				TemporarySecret s2 = new TemporarySecret(s, period, b2);
				currentSecrets.put(k, s2);
				created.add(s2);
			}
			if(!newSecrets.containsKey(k)) {
				TemporarySecret s3 = new TemporarySecret(s, period + 1, b3);
				newSecrets.put(k, s3);
				created.add(s3);
			}
		}
		return created;
	}

	public boolean stop() {
		synchLock.lock();
		try {
			eventBus.removeListener(this);
			timer.cancel();
			tagRecogniser.removeSecrets();
			maxLatencies.clear();
			oldSecrets.clear();
			currentSecrets.clear();
			newSecrets.clear();
			return true;
		} finally {
			synchLock.unlock();
		}
	}

	public StreamContext getStreamContext(ContactId c,
			TransportId t) {
		synchLock.lock();
		try {
			TemporarySecret s = currentSecrets.get(new EndpointKey(c, t));
			if(s == null) {
				LOG.info("No secret for endpoint");
				return null;
			}
			long streamNumber;
			try {
				streamNumber = db.incrementStreamCounter(c, t, s.getPeriod());
				if(streamNumber == -1) {
					LOG.info("No counter for period");
					return null;
				}
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				return null;
			}
			byte[] secret = s.getSecret();
			return new StreamContext(c, t, secret, streamNumber, s.getAlice());
		} finally {
			synchLock.unlock();
		}
	}

	public void endpointAdded(Endpoint ep, int maxLatency,
			byte[] initialSecret) {
		synchLock.lock();
		try {
			maxLatencies.put(ep.getTransportId(), maxLatency);
			// Work out which rotation period we're in
			long elapsed = clock.currentTimeMillis() - ep.getEpoch();
			long rotation = maxLatency + MAX_CLOCK_DIFFERENCE;
			long period = (elapsed / rotation) + 1;
			if(period < 1) throw new IllegalStateException();
			// Derive the old, current and new secrets
			byte[] b1 = initialSecret;
			for(long p = 0; p < period; p++)
				b1 = crypto.deriveNextSecret(b1, p);
			byte[] b2 = crypto.deriveNextSecret(b1, period);
			byte[] b3 = crypto.deriveNextSecret(b2, period + 1);
			TemporarySecret s1 = new TemporarySecret(ep, period - 1, b1);
			TemporarySecret s2 = new TemporarySecret(ep, period, b2);
			TemporarySecret s3 = new TemporarySecret(ep, period + 1, b3);
			// Add the incoming secrets to their respective maps
			EndpointKey k = new EndpointKey(ep);
			oldSecrets.put(k, s1);
			currentSecrets.put(k, s2);
			newSecrets.put(k, s3);
			// Store the new secrets
			try {
				db.addSecrets(Arrays.asList(s1, s2, s3));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				return;
			}
			// Pass the new secrets to the recogniser
			tagRecogniser.addSecret(s1);
			tagRecogniser.addSecret(s2);
			tagRecogniser.addSecret(s3);
		} finally {
			synchLock.unlock();
		}
	}

	@Override
	public void run() {
		synchLock.lock();
		try {
			// Rebuild the maps because we may be running a whole period late
			Collection<TemporarySecret> secrets = new ArrayList<TemporarySecret>();
			secrets.addAll(oldSecrets.values());
			secrets.addAll(currentSecrets.values());
			secrets.addAll(newSecrets.values());
			oldSecrets.clear();
			currentSecrets.clear();
			newSecrets.clear();
			// Work out what phase of its lifecycle each secret is in
			long now = clock.currentTimeMillis();
			Collection<TemporarySecret> dead = assignSecretsToMaps(now, secrets);
			// Remove any dead secrets from the recogniser
			for(TemporarySecret s : dead) {
				ContactId c = s.getContactId();
				TransportId t = s.getTransportId();
				long period = s.getPeriod();
				tagRecogniser.removeSecret(c, t, period);
			}
			// Replace any dead secrets
			Collection<TemporarySecret> created = replaceDeadSecrets(now, dead);
			if(!created.isEmpty()) {
				// Store any secrets that have been created
				try {
					db.addSecrets(created);
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				}
				// Pass any secrets that have been created to the recogniser
				for(TemporarySecret s : created) tagRecogniser.addSecret(s);
			}
		} finally {
			synchLock.unlock();
		}
	}

	public void eventOccurred(Event e) {
		if(e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			timer.schedule(new ContactRemovedTask(c), 0);
		} else if(e instanceof TransportAddedEvent) {
			TransportAddedEvent t = (TransportAddedEvent) e;
			timer.schedule(new TransportAddedTask(t), 0);
		} else if(e instanceof TransportRemovedEvent) {
			TransportRemovedEvent t = (TransportRemovedEvent) e;
			timer.schedule(new TransportRemovedTask(t), 0);
		}
	}

	// Locking: synchLock
	private void removeSecrets(ContactId c, Map<?, TemporarySecret> m) {
		Iterator<TemporarySecret> it = m.values().iterator();
		while(it.hasNext())
			if(it.next().getContactId().equals(c)) it.remove();
	}

	// Locking: synchLock
	private void removeSecrets(TransportId t, Map<?, TemporarySecret> m) {
		Iterator<TemporarySecret> it = m.values().iterator();
		while(it.hasNext())
			if(it.next().getTransportId().equals(t)) it.remove();
	}

	private static class EndpointKey {

		private final ContactId contactId;
		private final TransportId transportId;

		private EndpointKey(ContactId contactId, TransportId transportId) {
			this.contactId = contactId;
			this.transportId = transportId;
		}

		private EndpointKey(Endpoint ep) {
			this(ep.getContactId(), ep.getTransportId());
		}

		@Override
		public int hashCode() {
			return contactId.hashCode() ^ transportId.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if(o instanceof EndpointKey) {
				EndpointKey k = (EndpointKey) o;
				return contactId.equals(k.contactId) &&
						transportId.equals(k.transportId);
			}
			return false;
		}
	}

	private class ContactRemovedTask extends TimerTask {

		private final ContactRemovedEvent event;

		private ContactRemovedTask(ContactRemovedEvent event) {
			this.event = event;
		}

		@Override
		public void run() {
			ContactId c = event.getContactId();
			tagRecogniser.removeSecrets(c);
			synchLock.lock();
			try {
				removeSecrets(c, oldSecrets);
				removeSecrets(c, currentSecrets);
				removeSecrets(c, newSecrets);
			} finally {
				synchLock.unlock();
			}
		}
	}

	private class TransportAddedTask extends TimerTask {

		private final TransportAddedEvent event;

		private TransportAddedTask(TransportAddedEvent event) {
			this.event = event;
		}

		@Override
		public void run() {
			synchLock.lock();
			try {
				maxLatencies.put(event.getTransportId(), event.getMaxLatency());
			} finally {
				synchLock.unlock();
			}
		}
	}

	private class TransportRemovedTask extends TimerTask {

		private TransportRemovedEvent event;

		private TransportRemovedTask(TransportRemovedEvent event) {
			this.event = event;
		}

		@Override
		public void run() {
			TransportId t = event.getTransportId();
			tagRecogniser.removeSecrets(t);
			synchLock.lock();
			try {
				maxLatencies.remove(t);
				removeSecrets(t, oldSecrets);
				removeSecrets(t, currentSecrets);
				removeSecrets(t, newSecrets);
			} finally {
				synchLock.unlock();
			}
		}
	}
}
