package com.github.winplay02.gitcraft.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;

public record MavenCache(HashMap<String, String> shaUrlMap) {
	public MavenCache() {
		this(new HashMap<>());
	}

	public String getSha1ForURL(String urlSha1) throws IOException {
		if (!this.shaUrlMap.containsKey(urlSha1)) {
			String sha1 = null;
			try {
				sha1 = FileSystemNetworkManager.fetchAllFromURLSync(new URL(urlSha1));
			} catch (FileNotFoundException ignored) {
			} catch (URISyntaxException | InterruptedException e) {
				throw new IOException(e);
			}
			this.shaUrlMap.put(urlSha1, sha1);
		}

		return this.shaUrlMap.get(urlSha1);
	}
}
