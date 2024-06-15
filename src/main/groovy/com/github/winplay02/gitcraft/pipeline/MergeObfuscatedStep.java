package com.github.winplay02.gitcraft.pipeline;

import java.io.IOException;
import java.nio.file.Path;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RepoWrapper;

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
	protected Path getInputDirectory(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return pipelineCache.getForKey(Step.STEP_FETCH_ARTIFACTS);
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws IOException {
		if (mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) >= 0) {
			// only versions 1.3 and after can be merged before remapping
			return super.run(pipelineCache, mcVersion, mappingFlavour, versionGraph, repo);
		} else {
			return StepResult.NOT_RUN;
		}
	}
}
