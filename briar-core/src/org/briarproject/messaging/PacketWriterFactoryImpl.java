package org.briarproject.messaging;

import java.io.OutputStream;

import javax.inject.Inject;

import org.briarproject.api.data.WriterFactory;
import org.briarproject.api.messaging.PacketWriter;
import org.briarproject.api.messaging.PacketWriterFactory;

class PacketWriterFactoryImpl implements PacketWriterFactory {

	private final WriterFactory writerFactory;

	@Inject
	PacketWriterFactoryImpl(WriterFactory writerFactory) {
		this.writerFactory = writerFactory;
	}

	public PacketWriter createPacketWriter(OutputStream out) {
		return new PacketWriterImpl(writerFactory, out);
	}
}
