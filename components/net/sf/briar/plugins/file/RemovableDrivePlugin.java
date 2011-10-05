package net.sf.briar.plugins.file;

import java.io.File;
import java.io.IOException;
import java.util.List;

import net.sf.briar.api.TransportId;

class RemovableDrivePlugin extends FilePlugin {

	public static final int TRANSPORT_ID = 0;

	private static final TransportId id = new TransportId(TRANSPORT_ID);

	private final RemovableDriveFinder finder;

	RemovableDrivePlugin(RemovableDriveFinder finder) {
		this.finder = finder;
	}

	public TransportId getId() {
		return id;
	}

	@Override
	protected File chooseOutputDirectory() {
		try {
			List<File> drives = finder.findRemovableDrives();
			if(drives.isEmpty()) return null;
			String[] paths = new String[drives.size()];
			for(int i = 0; i < paths.length; i++) {
				paths[i] = drives.get(i).getPath();
			}
			int i = callback.showChoice("REMOVABLE_DRIVE_CHOOSE_DRIVE", paths);
			if(i == -1) return null;
			return drives.get(i);
		} catch(IOException e) {
			return null;
		}
	}

	@Override
	protected void writerFinished(File f) {
		callback.showMessage("REMOVABLE_DRIVE_WRITE_FINISHED");
	}
}