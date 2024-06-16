package com.github.winplay02.gitcraft.meta;

import com.github.winplay02.gitcraft.GitCraftConfig;

public record OrnitheFeatherVersionMeta(String gameVersion, String separator, int build, String maven, String version,
									boolean stable) implements Comparable<OrnitheFeatherVersionMeta> {
	@Override
	public int compareTo(OrnitheFeatherVersionMeta o) {
		return Integer.compare(this.build, o.build);
	}

	public String makeMavenURLMergedV2() {
		return String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather-gen%d/%s%s%s/feather-gen%d-%s%s%s-mergedv2.jar", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, gameVersion(), separator(), build(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, gameVersion(), separator(), build());
	}

	public String makeMavenURLUnmergedV2() {
		return String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather-gen%d/%s%s%s/feather-gen%d-%s%s%s-v2.jar", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, gameVersion(), separator(), build(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, gameVersion(), separator(), build());
	}

	public String makeMavenURLUnmergedV1() {
		return String.format("https://maven.ornithemc.net/releases/net/ornithemc/feather-gen%d/%s%s%s/feather-gen%d-%s%s%s.jar", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, gameVersion(), separator(), build(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, gameVersion(), separator(), build());
	}
}
