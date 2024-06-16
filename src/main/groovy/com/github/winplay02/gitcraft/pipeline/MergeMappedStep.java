package com.github.winplay02.gitcraft.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RepoWrapper;

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
		return RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "merged");
	}

	@Override
	protected Path getInputClientJar(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "client");
	}

	@Override
	protected Path getInputServerJar(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "server");
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws IOException {
		if (mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) < 0) {
			// only versions prior to 1.3 need merging after remapping

			if (mcVersion.hasClientCode() && mcVersion.hasServerCode()) {
				return super.run(pipelineCache, mcVersion, mappingFlavour, versionGraph, repo);
			}

			Path clientPath = getInputClientJar(pipelineCache, mcVersion, mappingFlavour);
			Path serverPath = getInputServerJar(pipelineCache, mcVersion, mappingFlavour);
			Path mergedMappedPath = getInternalArtifactPath(mcVersion, mappingFlavour);

			if (Files.exists(mergedMappedPath)) {
				return StepResult.UP_TO_DATE;
			}

			// a bit of a hack, but it makes it so there's a file path you can rely on existing
			// for steps after remapping (nesting, unpick, decompiling, etc.)
			if (mcVersion.hasClientCode()) {
				Files.copy(clientPath, mergedMappedPath);
			}
			if (mcVersion.hasServerCode()) {
				Files.copy(serverPath, mergedMappedPath);
			}

			return StepResult.SUCCESS;
		} else {
			return StepResult.NOT_RUN;
		}
	}
}
