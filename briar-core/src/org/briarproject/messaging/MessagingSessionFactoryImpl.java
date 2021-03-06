package org.briarproject.messaging;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import org.briarproject.api.ContactId;
import org.briarproject.api.TransportId;
import org.briarproject.api.crypto.CryptoExecutor;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DatabaseExecutor;
import org.briarproject.api.event.EventBus;
import org.briarproject.api.messaging.MessageVerifier;
import org.briarproject.api.messaging.MessagingSession;
import org.briarproject.api.messaging.MessagingSessionFactory;
import org.briarproject.api.messaging.PacketReader;
import org.briarproject.api.messaging.PacketReaderFactory;
import org.briarproject.api.messaging.PacketWriter;
import org.briarproject.api.messaging.PacketWriterFactory;
import org.briarproject.api.system.Clock;

class MessagingSessionFactoryImpl implements MessagingSessionFactory {

	private final DatabaseComponent db;
	private final Executor dbExecutor, cryptoExecutor;
	private final MessageVerifier messageVerifier;
	private final EventBus eventBus;
	private final Clock clock;
	private final PacketReaderFactory packetReaderFactory;
	private final PacketWriterFactory packetWriterFactory;

	@Inject
	MessagingSessionFactoryImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor,
			@CryptoExecutor Executor cryptoExecutor,
			MessageVerifier messageVerifier, EventBus eventBus, Clock clock,
			PacketReaderFactory packetReaderFactory,
			PacketWriterFactory packetWriterFactory) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.cryptoExecutor = cryptoExecutor;
		this.messageVerifier = messageVerifier;
		this.eventBus = eventBus;
		this.clock = clock;
		this.packetReaderFactory = packetReaderFactory;
		this.packetWriterFactory = packetWriterFactory;
	}

	public MessagingSession createIncomingSession(ContactId c, TransportId t,
			InputStream in) {
		PacketReader packetReader = packetReaderFactory.createPacketReader(in);
		return new IncomingSession(db, dbExecutor, cryptoExecutor, eventBus,
				messageVerifier, c, t, packetReader);
	}

	public MessagingSession createSimplexOutgoingSession(ContactId c,
			TransportId t, int maxLatency, OutputStream out) {
		PacketWriter packetWriter = packetWriterFactory.createPacketWriter(out);
		return new SimplexOutgoingSession(db, dbExecutor, eventBus, c, t,
				maxLatency, packetWriter);
	}

	public MessagingSession createDuplexOutgoingSession(ContactId c,
			TransportId t, int maxLatency, int maxIdleTime, OutputStream out) {
		PacketWriter packetWriter = packetWriterFactory.createPacketWriter(out);
		return new DuplexOutgoingSession(db, dbExecutor, eventBus, clock, c, t,
				maxLatency, maxIdleTime, packetWriter);
	}
}
