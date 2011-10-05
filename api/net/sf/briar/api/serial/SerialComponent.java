package net.sf.briar.api.serial;

public interface SerialComponent {

	int getSerialisedListEndLength();

	int getSerialisedListStartLength();

	int getSerialisedUniqueIdLength(int id);

	int getSerialisedUserDefinedIdLength(int id);
}