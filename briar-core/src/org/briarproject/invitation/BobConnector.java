package org.briarproject.invitation;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.logging.Logger;

import org.briarproject.api.Author;
import org.briarproject.api.AuthorFactory;
import org.briarproject.api.LocalAuthor;
import org.briarproject.api.TransportId;
import org.briarproject.api.TransportProperties;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.KeyManager;
import org.briarproject.api.crypto.PseudoRandom;
import org.briarproject.api.data.Reader;
import org.briarproject.api.data.ReaderFactory;
import org.briarproject.api.data.Writer;
import org.briarproject.api.data.WriterFactory;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.messaging.GroupFactory;
import org.briarproject.api.plugins.ConnectionManager;
import org.briarproject.api.plugins.duplex.DuplexPlugin;
import org.briarproject.api.plugins.duplex.DuplexTransportConnection;
import org.briarproject.api.system.Clock;
import org.briarproject.api.transport.StreamReaderFactory;
import org.briarproject.api.transport.StreamWriterFactory;

/** A connection thread for the peer being Bob in the invitation protocol. */
class BobConnector extends Connector {

	private static final Logger LOG =
			Logger.getLogger(BobConnector.class.getName());

	BobConnector(CryptoComponent crypto, DatabaseComponent db,
			ReaderFactory readerFactory, WriterFactory writerFactory,
			StreamReaderFactory streamReaderFactory,
			StreamWriterFactory streamWriterFactory,
			AuthorFactory authorFactory, GroupFactory groupFactory,
			KeyManager keyManager, ConnectionManager connectionManager,
			Clock clock, boolean reuseConnection, ConnectorGroup group,
			DuplexPlugin plugin, LocalAuthor localAuthor,
			Map<TransportId, TransportProperties> localProps,
			PseudoRandom random) {
		super(crypto, db, readerFactory, writerFactory, streamReaderFactory,
				streamWriterFactory, authorFactory, groupFactory, keyManager,
				connectionManager, clock, reuseConnection, group, plugin,
				localAuthor, localProps, random);
	}

	@Override
	public void run() {
		// Create an incoming or outgoing connection
		DuplexTransportConnection conn = createInvitationConnection();
		if(conn == null) return;
		if(LOG.isLoggable(INFO)) LOG.info(pluginName + " connected");
		// Carry out the key agreement protocol
		InputStream in;
		OutputStream out;
		Reader r;
		Writer w;
		byte[] secret;
		try {
			in = conn.getReader().getInputStream();
			out = conn.getWriter().getOutputStream();
			r = readerFactory.createReader(in);
			w = writerFactory.createWriter(out);
			// Alice goes first
			byte[] hash = receivePublicKeyHash(r);
			// Don't proceed with more than one connection
			if(group.getAndSetConnected()) {
				if(LOG.isLoggable(INFO)) LOG.info(pluginName + " redundant");
				tryToClose(conn, false);
				return;
			}
			sendPublicKeyHash(w);
			byte[] key = receivePublicKey(r);
			sendPublicKey(w);
			secret = deriveMasterSecret(hash, key, false);
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			group.keyAgreementFailed();
			tryToClose(conn, true);
			return;
		} catch(GeneralSecurityException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			group.keyAgreementFailed();
			tryToClose(conn, true);
			return;
		}
		// The key agreement succeeded - derive the confirmation codes
		if(LOG.isLoggable(INFO)) LOG.info(pluginName + " agreement succeeded");
		int[] codes = crypto.deriveConfirmationCodes(secret);
		int aliceCode = codes[0], bobCode = codes[1];
		group.keyAgreementSucceeded(bobCode, aliceCode);
		// Exchange confirmation results
		boolean localMatched, remoteMatched;
		try {
			remoteMatched = receiveConfirmation(r);
			localMatched = group.waitForLocalConfirmationResult();
			sendConfirmation(w, localMatched);
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			group.remoteConfirmationFailed();
			tryToClose(conn, true);
			return;
		} catch(InterruptedException e) {
			LOG.warning("Interrupted while waiting for confirmation");
			group.remoteConfirmationFailed();
			tryToClose(conn, true);
			Thread.currentThread().interrupt();
			return;
		}
		if(remoteMatched) group.remoteConfirmationSucceeded();
		else group.remoteConfirmationFailed();
		if(!(localMatched && remoteMatched)) {
			if(LOG.isLoggable(INFO))
				LOG.info(pluginName + " confirmation failed");
			tryToClose(conn, false);
			return;
		}
		// The timestamp is taken after exchanging confirmation results
		long localTimestamp = clock.currentTimeMillis();
		// Confirmation succeeded - upgrade to a secure connection
		if(LOG.isLoggable(INFO))
			LOG.info(pluginName + " confirmation succeeded");
		// Create the readers
		InputStream streamReader =
				streamReaderFactory.createInvitationStreamReader(in,
						secret, true); // Alice's stream
		r = readerFactory.createReader(streamReader);
		// Create the writers
		OutputStream streamWriter =
				streamWriterFactory.createInvitationStreamWriter(out,
						secret, false); // Bob's stream
		w = writerFactory.createWriter(streamWriter);
		// Derive the nonces
		byte[][] nonces = crypto.deriveInvitationNonces(secret);
		byte[] aliceNonce = nonces[0], bobNonce = nonces[1];
		// Exchange pseudonyms, signed nonces, timestamps and transports
		Author remoteAuthor;
		long remoteTimestamp;
		Map<TransportId, TransportProperties> remoteProps;
		boolean remoteReuseConnection;
		try {
			remoteAuthor = receivePseudonym(r, aliceNonce);
			remoteTimestamp = receiveTimestamp(r);
			remoteProps = receiveTransportProperties(r);
			remoteReuseConnection = receiveConfirmation(r);
			sendPseudonym(w, bobNonce);
			sendTimestamp(w, localTimestamp);
			sendTransportProperties(w);
			sendConfirmation(w, reuseConnection);
			// Close the outgoing stream and expect EOF on the incoming stream
			w.close();
			if(!r.eof()) LOG.warning("Unexpected data at end of connection");
		} catch(GeneralSecurityException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			group.pseudonymExchangeFailed();
			tryToClose(conn, true);
			return;
		} catch(IOException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			group.pseudonymExchangeFailed();
			tryToClose(conn, true);
			return;
		}
		// The epoch is the minimum of the peers' timestamps
		long epoch = Math.min(localTimestamp, remoteTimestamp);
		// Add the contact and store the transports
		try {
			addContact(remoteAuthor, remoteProps, secret, epoch, false);
		} catch(DbException e) {
			if(LOG.isLoggable(WARNING)) LOG.log(WARNING, e.toString(), e);
			tryToClose(conn, true);
			group.pseudonymExchangeFailed();
			return;
		}
		// Reuse the connection as a transport connection if both peers agree
		if(reuseConnection && remoteReuseConnection) reuseConnection(conn);
		else tryToClose(conn, false);
		// Pseudonym exchange succeeded
		if(LOG.isLoggable(INFO))
			LOG.info(pluginName + " pseudonym exchange succeeded");
		group.pseudonymExchangeSucceeded(remoteAuthor);
	}
}
