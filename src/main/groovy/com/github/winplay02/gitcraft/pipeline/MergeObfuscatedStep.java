package com.github.winplay02.gitcraft.pipeline;

import java.nio.file.Path;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;

public class MergeObfuscatedStep extends MergeStep {

	public MergeObfuscatedStep() {
		super(GitCraftPaths.MC_VERSION_STORE);
	}

	@Override
	public String getName() {
		return STEP_MERGE_OBFUSCATED;
	}

	@Override
	public boolean ignoresMappings() {
		return true;
	}

	@Override
	protected Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour _mappingFlavour) {
		return this.rootPath.resolve(mcVersion.launcherFriendlyVersionName()).resolve("merged-jar.jar");
	}

	@Override
	protected boolean shouldRun(OrderedVersion mcVersion) {
		return mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) >= 0;
	}
}
