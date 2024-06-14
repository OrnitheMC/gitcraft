package com.github.winplay02.gitcraft.meta;

import com.github.winplay02.gitcraft.GitCraftConfig;

public record OrnitheFeatherVersionMeta(String gameVersion, String separator, int build, String maven, String version,
									boolean stable, int intermediaryGen) implements Comparable<OrnitheFeatherVersionMeta> {
	public OrnitheFeatherVersionMeta(String gameVersion, String separator, int build, String maven, String version, boolean stable) {
		this(gameVersion, separator, build, maven, version, stable, GitCraftConfig.ORNITHE_INTERMEDIARY_GEN);
	}

	@Override
	public int compareTo(OrnitheFeatherVersionMeta o) {
		return Integer.compare(this.build, o.build);
	}

	public String makeMavenURLMergedV2() {
		return String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather-gen%d/%s%s%s/feather-gen%d-%s%s%s-mergedv2.jar", intermediaryGen(), gameVersion(), separator(), build(), intermediaryGen(), gameVersion(), separator(), build());
	}

	public String makeMavenURLUnmergedV2() {
		return String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather-gen%d/%s%s%s/feather-gen%d-%s%s%s-v2.jar", intermediaryGen(), gameVersion(), separator(), build(), intermediaryGen(), gameVersion(), separator(), build());
	}

	public String makeMavenURLUnmergedV1() {
		return String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather-gen%d/%s%s%s/feather-gen%d-%s%s%s.jar", intermediaryGen(), gameVersion(), separator(), build(), intermediaryGen(), gameVersion(), separator(), build());
	}
}
