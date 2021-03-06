package org.briarproject.crypto;

import java.io.InputStream;

import javax.inject.Inject;
import javax.inject.Provider;

import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.SecretKey;
import org.briarproject.api.crypto.StreamDecrypter;
import org.briarproject.api.crypto.StreamDecrypterFactory;
import org.briarproject.api.transport.StreamContext;

class StreamDecrypterFactoryImpl implements StreamDecrypterFactory {

	private final CryptoComponent crypto;
	private final Provider<AuthenticatedCipher> cipherProvider;

	@Inject
	StreamDecrypterFactoryImpl(CryptoComponent crypto,
			Provider<AuthenticatedCipher> cipherProvider) {
		this.crypto = crypto;
		this.cipherProvider = cipherProvider;
	}

	public StreamDecrypter createStreamDecrypter(InputStream in,
			StreamContext ctx) {
		// Derive the frame key
		byte[] secret = ctx.getSecret();
		long streamNumber = ctx.getStreamNumber();
		boolean alice = !ctx.getAlice();
		SecretKey frameKey = crypto.deriveFrameKey(secret, streamNumber, alice);
		// Create the decrypter
		AuthenticatedCipher cipher = cipherProvider.get();
		return new StreamDecrypterImpl(in, cipher, frameKey);
	}

	public StreamDecrypter createInvitationStreamDecrypter(InputStream in,
			byte[] secret, boolean alice) {
		// Derive the frame key
		SecretKey frameKey = crypto.deriveFrameKey(secret, 0, alice);
		// Create the decrypter
		AuthenticatedCipher cipher = cipherProvider.get();
		return new StreamDecrypterImpl(in, cipher, frameKey);
	}
}
