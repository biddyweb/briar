package net.sf.briar.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

import junit.framework.TestCase;
import net.sf.briar.api.protocol.Ack;
import net.sf.briar.api.protocol.BatchId;
import net.sf.briar.api.protocol.Tags;
import net.sf.briar.api.protocol.UniqueId;
import net.sf.briar.api.serial.FormatException;
import net.sf.briar.api.serial.Reader;
import net.sf.briar.api.serial.ReaderFactory;
import net.sf.briar.api.serial.Writer;
import net.sf.briar.api.serial.WriterFactory;
import net.sf.briar.serial.SerialModule;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class AckReaderTest extends TestCase {

	private final ReaderFactory readerFactory;
	private final WriterFactory writerFactory;
	private final Mockery context;

	public AckReaderTest() throws Exception {
		super();
		Injector i = Guice.createInjector(new SerialModule());
		readerFactory = i.getInstance(ReaderFactory.class);
		writerFactory = i.getInstance(WriterFactory.class);
		context = new Mockery();
	}

	@Test
	public void testFormatExceptionIfAckIsTooLarge() throws Exception {
		AckFactory ackFactory = context.mock(AckFactory.class);
		AckReader ackReader = new AckReader(new BatchIdReader(), ackFactory);

		byte[] b = createAck(true);
		ByteArrayInputStream in = new ByteArrayInputStream(b);
		Reader reader = readerFactory.createReader(in);
		reader.addObjectReader(Tags.ACK, ackReader);

		try {
			reader.readUserDefined(Tags.ACK, Ack.class);
			assertTrue(false);
		} catch(FormatException expected) {}
		context.assertIsSatisfied();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testNoFormatExceptionIfAckIsMaximumSize() throws Exception {
		final AckFactory ackFactory = context.mock(AckFactory.class);
		AckReader ackReader = new AckReader(new BatchIdReader(), ackFactory);
		final Ack ack = context.mock(Ack.class);
		context.checking(new Expectations() {{
			oneOf(ackFactory).createAck(with(any(Collection.class)));
			will(returnValue(ack));
		}});

		byte[] b = createAck(false);
		ByteArrayInputStream in = new ByteArrayInputStream(b);
		Reader reader = readerFactory.createReader(in);
		reader.addObjectReader(Tags.ACK, ackReader);

		assertEquals(ack, reader.readUserDefined(Tags.ACK, Ack.class));
		context.assertIsSatisfied();
	}

	@Test
	public void testEmptyAck() throws Exception {
		final AckFactory ackFactory = context.mock(AckFactory.class);
		AckReader ackReader = new AckReader(new BatchIdReader(), ackFactory);
		final Ack ack = context.mock(Ack.class);
		context.checking(new Expectations() {{
			oneOf(ackFactory).createAck(
					with(Collections.<BatchId>emptyList()));
			will(returnValue(ack));
		}});

		byte[] b = createEmptyAck();
		ByteArrayInputStream in = new ByteArrayInputStream(b);
		Reader reader = readerFactory.createReader(in);
		reader.addObjectReader(Tags.ACK, ackReader);

		assertEquals(ack, reader.readUserDefined(Tags.ACK, Ack.class));
		context.assertIsSatisfied();
	}

	private byte[] createAck(boolean tooBig) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Ack.MAX_SIZE);
		Writer w = writerFactory.createWriter(out);
		w.writeUserDefinedTag(Tags.ACK);
		w.writeListStart();
		byte[] b = new byte[UniqueId.LENGTH];
		Random random = new Random();
		while(out.size() < Ack.MAX_SIZE - BatchId.SERIALISED_LENGTH) {
			w.writeUserDefinedTag(Tags.BATCH_ID);
			random.nextBytes(b);
			w.writeBytes(b);
		}
		if(tooBig) {
			w.writeUserDefinedTag(Tags.BATCH_ID);
			random.nextBytes(b);
			w.writeBytes(b);
		}
		w.writeListEnd();
		assertEquals(tooBig, out.size() > Ack.MAX_SIZE);
		return out.toByteArray();
	}

	private byte[] createEmptyAck() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Writer w = writerFactory.createWriter(out);
		w.writeUserDefinedTag(Tags.ACK);
		w.writeListStart();
		w.writeListEnd();
		return out.toByteArray();
	}
}