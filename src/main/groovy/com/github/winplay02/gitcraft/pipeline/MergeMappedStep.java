package com.github.winplay02.gitcraft.pipeline;

import java.nio.file.Path;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;

public class MergeMappedStep extends MergeStep {

	public MergeMappedStep() {
		super(GitCraftPaths.REMAPPED);
	}

	@Override
	public String getName() {
		return STEP_MERGE_MAPPED;
	}

	@Override
	public boolean ignoresMappings() {
		return false;
	}

	@Override
	protected Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return this.rootPath.resolve(mcVersion.launcherFriendlyVersionName()).resolve(String.format("%s-merged.jar", mappingFlavour.toString()));
	}

	@Override
	protected boolean shouldRun(OrderedVersion mcVersion) {
		return mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) < 0;
	}
}
