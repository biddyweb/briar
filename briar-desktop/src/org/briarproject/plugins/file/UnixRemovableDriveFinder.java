package org.briarproject.plugins.file;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

abstract class UnixRemovableDriveFinder implements RemovableDriveFinder {

	protected abstract String getMountCommand();
	protected abstract String parseMountPoint(String line);
	protected abstract boolean isRemovableDriveMountPoint(String path);

	public List<File> findRemovableDrives() throws IOException {
		List<File> drives = new ArrayList<File>();
		Process p = new ProcessBuilder(getMountCommand()).start();
		Scanner s = new Scanner(p.getInputStream(), "UTF-8");
		try {
			while(s.hasNextLine()) {
				String line = s.nextLine();
				String[] tokens = line.split(" ");
				if(tokens.length < 3) continue;
				// The general format is "/dev/foo on /bar/baz ..."
				if(tokens[0].startsWith("/dev/") && tokens[1].equals("on")) {
					// The path may contain spaces so we can't use tokens[2]
					String path = parseMountPoint(line);
					if(isRemovableDriveMountPoint(path)) {
						File f = new File(path);
						if(f.exists() && f.isDirectory()) drives.add(f);
					}
				}
			}
		} finally {
			s.close();
		}
		return Collections.unmodifiableList(drives);
	}
}
