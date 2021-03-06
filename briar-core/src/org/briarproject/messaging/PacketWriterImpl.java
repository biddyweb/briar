package org.briarproject.messaging;

import static org.briarproject.api.data.DataConstants.LIST_END_LENGTH;
import static org.briarproject.api.data.DataConstants.LIST_START_LENGTH;
import static org.briarproject.api.data.DataConstants.UNIQUE_ID_LENGTH;
import static org.briarproject.api.messaging.MessagingConstants.HEADER_LENGTH;
import static org.briarproject.api.messaging.MessagingConstants.MAX_PAYLOAD_LENGTH;
import static org.briarproject.api.messaging.MessagingConstants.PROTOCOL_VERSION;
import static org.briarproject.api.messaging.PacketTypes.ACK;
import static org.briarproject.api.messaging.PacketTypes.OFFER;
import static org.briarproject.api.messaging.PacketTypes.REQUEST;
import static org.briarproject.api.messaging.PacketTypes.RETENTION_ACK;
import static org.briarproject.api.messaging.PacketTypes.RETENTION_UPDATE;
import static org.briarproject.api.messaging.PacketTypes.SUBSCRIPTION_ACK;
import static org.briarproject.api.messaging.PacketTypes.SUBSCRIPTION_UPDATE;
import static org.briarproject.api.messaging.PacketTypes.TRANSPORT_ACK;
import static org.briarproject.api.messaging.PacketTypes.TRANSPORT_UPDATE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.briarproject.api.data.Writer;
import org.briarproject.api.data.WriterFactory;
import org.briarproject.api.messaging.Ack;
import org.briarproject.api.messaging.Group;
import org.briarproject.api.messaging.MessageId;
import org.briarproject.api.messaging.Offer;
import org.briarproject.api.messaging.PacketTypes;
import org.briarproject.api.messaging.PacketWriter;
import org.briarproject.api.messaging.Request;
import org.briarproject.api.messaging.RetentionAck;
import org.briarproject.api.messaging.RetentionUpdate;
import org.briarproject.api.messaging.SubscriptionAck;
import org.briarproject.api.messaging.SubscriptionUpdate;
import org.briarproject.api.messaging.TransportAck;
import org.briarproject.api.messaging.TransportUpdate;
import org.briarproject.util.ByteUtils;

// This class is not thread-safe
class PacketWriterImpl implements PacketWriter {

	private final WriterFactory writerFactory;
	private final OutputStream out;
	private final byte[] header;
	private final ByteArrayOutputStream payload;

	PacketWriterImpl(WriterFactory writerFactory, OutputStream out) {
		this.writerFactory = writerFactory;
		this.out = out;
		header = new byte[HEADER_LENGTH];
		header[0] = PROTOCOL_VERSION;
		payload = new ByteArrayOutputStream(MAX_PAYLOAD_LENGTH);
	}

	public int getMaxMessagesForAck(long capacity) {
		return getMaxMessagesForPacket(capacity);
	}

	public int getMaxMessagesForRequest(long capacity) {
		return getMaxMessagesForPacket(capacity);
	}

	public int getMaxMessagesForOffer(long capacity) {
		return getMaxMessagesForPacket(capacity);
	}

	private int getMaxMessagesForPacket(long capacity) {
		int payload = (int) Math.min(capacity - HEADER_LENGTH,
				MAX_PAYLOAD_LENGTH);
		int overhead = LIST_START_LENGTH * 2 + LIST_END_LENGTH * 2;
		return (payload - overhead) / UNIQUE_ID_LENGTH;
	}

	private void writePacket(byte packetType) throws IOException {
		header[1] = packetType;
		ByteUtils.writeUint16(payload.size(), header, 2);
		out.write(header);
		payload.writeTo(out);
		payload.reset();
	}

	public void writeAck(Ack a) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeListStart();
		for(MessageId m : a.getMessageIds()) w.writeRaw(m.getBytes());
		w.writeListEnd();
		w.writeListEnd();
		writePacket(ACK);
	}

	public void writeMessage(byte[] raw) throws IOException {
		header[1] = PacketTypes.MESSAGE;
		ByteUtils.writeUint16(raw.length, header, 2);
		out.write(header);
		out.write(raw);
	}

	public void writeOffer(Offer o) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeListStart();
		for(MessageId m : o.getMessageIds()) w.writeRaw(m.getBytes());
		w.writeListEnd();
		w.writeListEnd();
		writePacket(OFFER);
	}

	public void writeRequest(Request r) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeListStart();
		for(MessageId m : r.getMessageIds()) w.writeRaw(m.getBytes());
		w.writeListEnd();
		w.writeListEnd();
		writePacket(REQUEST);
	}

	public void writeRetentionAck(RetentionAck a) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeInteger(a.getVersion());
		w.writeListEnd();
		writePacket(RETENTION_ACK);
	}

	public void writeRetentionUpdate(RetentionUpdate u) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeInteger(u.getRetentionTime());
		w.writeInteger(u.getVersion());
		w.writeListEnd();
		writePacket(RETENTION_UPDATE);
	}

	public void writeSubscriptionAck(SubscriptionAck a) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeInteger(a.getVersion());
		w.writeListEnd();
		writePacket(SUBSCRIPTION_ACK);
	}

	public void writeSubscriptionUpdate(SubscriptionUpdate u)
			throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeListStart();
		for(Group g : u.getGroups()) {
			w.writeListStart();
			w.writeString(g.getName());
			w.writeRaw(g.getSalt());
			w.writeListEnd();
		}
		w.writeListEnd();
		w.writeInteger(u.getVersion());
		w.writeListEnd();
		writePacket(SUBSCRIPTION_UPDATE);
	}

	public void writeTransportAck(TransportAck a) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeString(a.getId().getString());
		w.writeInteger(a.getVersion());
		w.writeListEnd();
		writePacket(TRANSPORT_ACK);
	}

	public void writeTransportUpdate(TransportUpdate u) throws IOException {
		assert payload.size() == 0;
		Writer w = writerFactory.createWriter(payload);
		w.writeListStart();
		w.writeString(u.getId().getString());
		w.writeMap(u.getProperties());
		w.writeInteger(u.getVersion());
		w.writeListEnd();
		writePacket(TRANSPORT_UPDATE);
	}

	public void flush() throws IOException {
		out.flush();
	}
}
