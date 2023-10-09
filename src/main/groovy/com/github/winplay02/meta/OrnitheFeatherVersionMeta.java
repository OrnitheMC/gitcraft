package com.github.winplay02.meta;

import com.github.winplay02.RemoteHelper;

public record OrnitheFeatherVersionMeta(String gameVersion, String separator, int build, String maven, String version,
									boolean stable) implements Comparable<OrnitheFeatherVersionMeta> {
	@Override
	public int compareTo(OrnitheFeatherVersionMeta o) {
		return Integer.compare(this.build, o.build);
	}

	public String makeMavenURLMergedV2() {
		return RemoteHelper.urlencodedURL(String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather/%s%s%s/feather-%s%s%s-mergedv2.jar", gameVersion(), separator(), build(), gameVersion(), separator(), build()));
	}

	public String makeMavenURLUnmergedV2() {
		return RemoteHelper.urlencodedURL(String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather/%s%s%s/feather-%s%s%s-v2.jar", gameVersion(), separator(), build(), gameVersion(), separator(), build()));
	}

	public String makeMavenURLUnmergedV1() {
		return RemoteHelper.urlencodedURL(String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather/%s%s%s/feather-%s%s%s.jar", gameVersion(), separator(), build(), gameVersion(), separator(), build()));
	}
}
