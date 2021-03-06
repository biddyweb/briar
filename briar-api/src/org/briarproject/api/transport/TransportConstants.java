package org.briarproject.api.transport;


public interface TransportConstants {

	/** The length of the pseudo-random tag in bytes. */
	int TAG_LENGTH = 16;

	/** The maximum length of a frame in bytes, including the header and MAC. */
	int MAX_FRAME_LENGTH = 1024;

	/** The length of the message authentication code (MAC) in bytes. */
	int MAC_LENGTH = 16;

	/** The length of the frame header in bytes. */
	int HEADER_LENGTH = 4 + MAC_LENGTH;

	/** The maximum total length of the frame payload and padding in bytes. */
	int MAX_PAYLOAD_LENGTH = MAX_FRAME_LENGTH - HEADER_LENGTH - MAC_LENGTH;

	/** The length of the initalisation vector (IV) in bytes. */
	int IV_LENGTH = 12;

	/**
	 * The minimum stream length in bytes that all transport plugins must
	 * support. Streams may be shorter than this length, but all transport
	 * plugins must support streams of at least this length.
	 */
	int MIN_STREAM_LENGTH = 64 * 1024; // 64 KiB

	/** The maximum difference between two communicating devices' clocks. */
	int MAX_CLOCK_DIFFERENCE = 60 * 60 * 1000; // 1 hour

	/** The size of the reordering window. */
	int REORDERING_WINDOW_SIZE = 32;
}
