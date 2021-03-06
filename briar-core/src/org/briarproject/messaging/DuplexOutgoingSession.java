package org.briarproject.messaging;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.api.messaging.MessagingConstants.MAX_PAYLOAD_LENGTH;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import org.briarproject.api.ContactId;
import org.briarproject.api.TransportId;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.event.ContactRemovedEvent;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.LocalSubscriptionsUpdatedEvent;
import org.briarproject.api.event.LocalTransportsUpdatedEvent;
import org.briarproject.api.event.MessageAddedEvent;
import org.briarproject.api.event.MessageExpiredEvent;
import org.briarproject.api.event.MessageRequestedEvent;
import org.briarproject.api.event.MessageToAckEvent;
import org.briarproject.api.event.MessageToRequestEvent;
import org.briarproject.api.event.RemoteRetentionTimeUpdatedEvent;
import org.briarproject.api.event.RemoteSubscriptionsUpdatedEvent;
import org.briarproject.api.event.RemoteTransportsUpdatedEvent;
import org.briarproject.api.event.ShutdownEvent;
import org.briarproject.api.event.TransportRemovedEvent;
import org.briarproject.api.messaging.Ack;
import org.briarproject.api.messaging.MessagingSession;
import org.briarproject.api.messaging.Offer;
import org.briarproject.api.messaging.PacketWriter;
import org.briarproject.api.messaging.Request;
import org.briarproject.api.messaging.RetentionAck;
import org.briarproject.api.messaging.RetentionUpdate;
import org.briarproject.api.messaging.SubscriptionAck;
import org.briarproject.api.messaging.SubscriptionUpdate;
import org.briarproject.api.messaging.TransportAck;
import org.briarproject.api.messaging.TransportUpdate;
import org.briarproject.api.system.Clock;

/**
 * An outgoing {@link org.briarproject.api.messaging.MessagingSession
 * MessagingSession} suitable for duplex transports. The session offers
 * messages before sending them, keeps its output stream open when there are no
 * packets to send, and reacts to events that make packets available to send.
 */
class DuplexOutgoingSession implements MessagingSession, EventListener {

	// Check for retransmittable packets once every 60 seconds
	private static final int RETX_QUERY_INTERVAL = 60 * 1000;
	private static final Logger LOG =
			Logger.getLogger(DuplexOutgoingSession.class.getName());

	private static final ThrowingRunnable<IOException> CLOSE =
			new ThrowingRunnable<IOException>() {
		public void run() {}
	};

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final EventBus eventBus;
	private final Clock clock;
	private final ContactId contactId;
	private final TransportId transportId;
	private final int maxLatency, maxIdleTime;
	private final PacketWriter packetWriter;
	private final BlockingQueue<ThrowingRunnable<IOException>> writerTasks;

	// The following must only be accessed on the writer thread
	private long nextKeepalive = 0, nextRetxQuery = 0;
	private boolean dataToFlush = true;

	private volatile boolean interrupted = false;

	DuplexOutgoingSession(DatabaseComponent db, Executor dbExecutor,
			EventBus eventBus, Clock clock, ContactId contactId,
			TransportId transportId, int maxLatency, int maxIdleTime,
			PacketWriter packetWriter) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.eventBus = eventBus;
		this.clock = clock;
		this.contactId = contactId;
		this.transportId = transportId;
		this.maxLatency = maxLatency;
		this.maxIdleTime = maxIdleTime;
		this.packetWriter = packetWriter;
		writerTasks = new LinkedBlockingQueue<ThrowingRunnable<IOException>>();
	}

	public void run() throws IOException {
		eventBus.addListener(this);
		try {
			// Start a query for each type of packet, in order of urgency
			dbExecutor.execute(new GenerateTransportAcks());
			dbExecutor.execute(new GenerateTransportUpdates());
			dbExecutor.execute(new GenerateSubscriptionAck());
			dbExecutor.execute(new GenerateSubscriptionUpdate());
			dbExecutor.execute(new GenerateRetentionAck());
			dbExecutor.execute(new GenerateRetentionUpdate());
			dbExecutor.execute(new GenerateAck());
			dbExecutor.execute(new GenerateBatch());
			dbExecutor.execute(new GenerateOffer());
			dbExecutor.execute(new GenerateRequest());
			long now = clock.currentTimeMillis();
			nextKeepalive = now + maxIdleTime;
			nextRetxQuery = now + RETX_QUERY_INTERVAL;
			// Write packets until interrupted
			try {
				while(!interrupted) {
					// Work out how long we should wait for a packet
					now = clock.currentTimeMillis();
					long wait = Math.min(nextKeepalive, nextRetxQuery) - now;
					if(wait < 0) wait = 0;
					// Flush any unflushed data if we're going to wait
					if(wait > 0 && dataToFlush && writerTasks.isEmpty()) {
						packetWriter.flush();
						dataToFlush = false;
						nextKeepalive = now + maxIdleTime;
					}
					// Wait for a packet
					ThrowingRunnable<IOException> task = writerTasks.poll(wait,
							MILLISECONDS);
					if(task == null) {
						now = clock.currentTimeMillis();
						if(now >= nextRetxQuery) {
							// Check for retransmittable packets
							dbExecutor.execute(new GenerateTransportUpdates());
							dbExecutor.execute(new GenerateSubscriptionUpdate());
							dbExecutor.execute(new GenerateRetentionUpdate());
							dbExecutor.execute(new GenerateBatch());
							dbExecutor.execute(new GenerateOffer());
							nextRetxQuery = now + RETX_QUERY_INTERVAL;
						}
						if(now >= nextKeepalive) {
							// Flush the stream to keep it alive
							packetWriter.flush();
							dataToFlush = false;
							nextKeepalive = now + maxIdleTime;
						}
					} else if(task == CLOSE) {
						break;
					} else {
						task.run();
						dataToFlush = true;
					}
				}
				if(dataToFlush) packetWriter.flush();
			} catch(InterruptedException e) {
				LOG.info("Interrupted while waiting for a packet to write");
				Thread.currentThread().interrupt();
			}
		} finally {
			eventBus.removeListener(this);
		}
	}

	public void interrupt() {
		interrupted = true;
		writerTasks.add(CLOSE);
	}

	public void eventOccurred(Event e) {
		if(e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if(c.getContactId().equals(contactId)) interrupt();
		} else if(e instanceof MessageAddedEvent) {
			dbExecutor.execute(new GenerateOffer());
		} else if(e instanceof MessageExpiredEvent) {
			dbExecutor.execute(new GenerateRetentionUpdate());
		} else if(e instanceof LocalSubscriptionsUpdatedEvent) {
			LocalSubscriptionsUpdatedEvent l =
					(LocalSubscriptionsUpdatedEvent) e;
			if(l.getAffectedContacts().contains(contactId)) {
				dbExecutor.execute(new GenerateSubscriptionUpdate());
				dbExecutor.execute(new GenerateOffer());
			}
		} else if(e instanceof LocalTransportsUpdatedEvent) {
			dbExecutor.execute(new GenerateTransportUpdates());
		} else if(e instanceof MessageRequestedEvent) {
			if(((MessageRequestedEvent) e).getContactId().equals(contactId))
				dbExecutor.execute(new GenerateBatch());
		} else if(e instanceof MessageToAckEvent) {
			if(((MessageToAckEvent) e).getContactId().equals(contactId))
				dbExecutor.execute(new GenerateAck());
		} else if(e instanceof MessageToRequestEvent) {
			if(((MessageToRequestEvent) e).getContactId().equals(contactId))
				dbExecutor.execute(new GenerateRequest());
		} else if(e instanceof RemoteRetentionTimeUpdatedEvent) {
			RemoteRetentionTimeUpdatedEvent r =
					(RemoteRetentionTimeUpdatedEvent) e;
			if(r.getContactId().equals(contactId))
				dbExecutor.execute(new GenerateRetentionAck());
		} else if(e instanceof RemoteSubscriptionsUpdatedEvent) {
			RemoteSubscriptionsUpdatedEvent r =
					(RemoteSubscriptionsUpdatedEvent) e;
			if(r.getContactId().equals(contactId)) {
				dbExecutor.execute(new GenerateSubscriptionAck());
				dbExecutor.execute(new GenerateOffer());
			}
		} else if(e instanceof RemoteTransportsUpdatedEvent) {
			RemoteTransportsUpdatedEvent r =
					(RemoteTransportsUpdatedEvent) e;
			if(r.getContactId().equals(contactId))
				dbExecutor.execute(new GenerateTransportAcks());
		} else if(e instanceof ShutdownEvent) {
			interrupt();
		} else if(e instanceof TransportRemovedEvent) {
			TransportRemovedEvent t = (TransportRemovedEvent) e;
			if(t.getTransportId().equals(transportId)) interrupt();
		}
	}

	// This task runs on the database thread
	private class GenerateAck implements Runnable {

		public void run() {
			if(interrupted) return;
			int maxMessages = packetWriter.getMaxMessagesForAck(Long.MAX_VALUE);
			try {
				Ack a = db.generateAck(contactId, maxMessages);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated ack: " + (a != null));
				if(a != null) writerTasks.add(new WriteAck(a));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteAck implements ThrowingRunnable<IOException> {

		private final Ack ack;

		private WriteAck(Ack ack) {
			this.ack = ack;
		}

		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeAck(ack);
			LOG.info("Sent ack");
			dbExecutor.execute(new GenerateAck());
		}
	}

	// This task runs on the database thread
	private class GenerateBatch implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				Collection<byte[]> b = db.generateRequestedBatch(contactId,
						MAX_PAYLOAD_LENGTH, maxLatency);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated batch: " + (b != null));
				if(b != null) writerTasks.add(new WriteBatch(b));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteBatch implements ThrowingRunnable<IOException> {

		private final Collection<byte[]> batch;

		private WriteBatch(Collection<byte[]> batch) {
			this.batch = batch;
		}

		public void run() throws IOException {
			if(interrupted) return;
			for(byte[] raw : batch) packetWriter.writeMessage(raw);
			LOG.info("Sent batch");
			dbExecutor.execute(new GenerateBatch());
		}
	}

	// This task runs on the database thread
	private class GenerateOffer implements Runnable {

		public void run() {
			if(interrupted) return;
			int maxMessages = packetWriter.getMaxMessagesForOffer(
					Long.MAX_VALUE);
			try {
				Offer o = db.generateOffer(contactId, maxMessages, maxLatency);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated offer: " + (o != null));
				if(o != null) writerTasks.add(new WriteOffer(o));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteOffer implements ThrowingRunnable<IOException> {

		private final Offer offer;

		private WriteOffer(Offer offer) {
			this.offer = offer;
		}

		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeOffer(offer);
			LOG.info("Sent offer");
			dbExecutor.execute(new GenerateOffer());
		}
	}

	// This task runs on the database thread
	private class GenerateRequest implements Runnable {

		public void run() {
			if(interrupted) return;
			int maxMessages = packetWriter.getMaxMessagesForRequest(
					Long.MAX_VALUE);
			try {
				Request r = db.generateRequest(contactId, maxMessages);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated request: " + (r != null));
				if(r != null) writerTasks.add(new WriteRequest(r));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteRequest implements ThrowingRunnable<IOException> {

		private final Request request;

		private WriteRequest(Request request) {
			this.request = request;
		}

		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeRequest(request);
			LOG.info("Sent request");
			dbExecutor.execute(new GenerateRequest());
		}
	}

	// This task runs on the database thread
	private class GenerateRetentionAck implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				RetentionAck a = db.generateRetentionAck(contactId);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated retention ack: " + (a != null));
				if(a != null) writerTasks.add(new WriteRetentionAck(a));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This tasks runs on the writer thread
	private class WriteRetentionAck implements ThrowingRunnable<IOException> {

		private final RetentionAck ack;

		private WriteRetentionAck(RetentionAck ack) {
			this.ack = ack;
		}


		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeRetentionAck(ack);
			LOG.info("Sent retention ack");
			dbExecutor.execute(new GenerateRetentionAck());
		}
	}

	// This task runs on the database thread
	private class GenerateRetentionUpdate implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				RetentionUpdate u =
						db.generateRetentionUpdate(contactId, maxLatency);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated retention update: " + (u != null));
				if(u != null) writerTasks.add(new WriteRetentionUpdate(u));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteRetentionUpdate
	implements ThrowingRunnable<IOException> {

		private final RetentionUpdate update;

		private WriteRetentionUpdate(RetentionUpdate update) {
			this.update = update;
		}

		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeRetentionUpdate(update);
			LOG.info("Sent retention update");
			dbExecutor.execute(new GenerateRetentionUpdate());
		}
	}

	// This task runs on the database thread
	private class GenerateSubscriptionAck implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				SubscriptionAck a = db.generateSubscriptionAck(contactId);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated subscription ack: " + (a != null));
				if(a != null) writerTasks.add(new WriteSubscriptionAck(a));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This tasks runs on the writer thread
	private class WriteSubscriptionAck
	implements ThrowingRunnable<IOException> {

		private final SubscriptionAck ack;

		private WriteSubscriptionAck(SubscriptionAck ack) {
			this.ack = ack;
		}

		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeSubscriptionAck(ack);
			LOG.info("Sent subscription ack");
			dbExecutor.execute(new GenerateSubscriptionAck());
		}
	}

	// This task runs on the database thread
	private class GenerateSubscriptionUpdate implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				SubscriptionUpdate u =
						db.generateSubscriptionUpdate(contactId, maxLatency);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated subscription update: " + (u != null));
				if(u != null) writerTasks.add(new WriteSubscriptionUpdate(u));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteSubscriptionUpdate
	implements ThrowingRunnable<IOException> {

		private final SubscriptionUpdate update;

		private WriteSubscriptionUpdate(SubscriptionUpdate update) {
			this.update = update;
		}

		public void run() throws IOException {
			if(interrupted) return;
			packetWriter.writeSubscriptionUpdate(update);
			LOG.info("Sent subscription update");
			dbExecutor.execute(new GenerateSubscriptionUpdate());
		}
	}

	// This task runs on the database thread
	private class GenerateTransportAcks implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				Collection<TransportAck> acks =
						db.generateTransportAcks(contactId);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated transport acks: " + (acks != null));
				if(acks != null) writerTasks.add(new WriteTransportAcks(acks));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This tasks runs on the writer thread
	private class WriteTransportAcks implements ThrowingRunnable<IOException> {

		private final Collection<TransportAck> acks;

		private WriteTransportAcks(Collection<TransportAck> acks) {
			this.acks = acks;
		}

		public void run() throws IOException {
			if(interrupted) return;
			for(TransportAck a : acks) packetWriter.writeTransportAck(a);
			LOG.info("Sent transport acks");
			dbExecutor.execute(new GenerateTransportAcks());
		}
	}

	// This task runs on the database thread
	private class GenerateTransportUpdates implements Runnable {

		public void run() {
			if(interrupted) return;
			try {
				Collection<TransportUpdate> t =
						db.generateTransportUpdates(contactId, maxLatency);
				if(LOG.isLoggable(INFO))
					LOG.info("Generated transport updates: " + (t != null));
				if(t != null) writerTasks.add(new WriteTransportUpdates(t));
			} catch(DbException e) {
				if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
				interrupt();
			}
		}
	}

	// This task runs on the writer thread
	private class WriteTransportUpdates
	implements ThrowingRunnable<IOException> {

		private final Collection<TransportUpdate> updates;

		private WriteTransportUpdates(Collection<TransportUpdate> updates) {
			this.updates = updates;
		}

		public void run() throws IOException {
			if(interrupted) return;
			for(TransportUpdate u : updates)
				packetWriter.writeTransportUpdate(u);
			LOG.info("Sent transport updates");
			dbExecutor.execute(new GenerateTransportUpdates());
		}
	}
}
