package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class RemapStep extends Step {

	private final Path rootPath;

	public RemapStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public RemapStep() {
		this(GitCraftPaths.REMAPPED);
	}

	@Override
	public String getName() {
		return Step.STEP_REMAP;
	}

	@Override
	protected Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour _mappingFlavour) {
		return this.rootPath.resolve(String.format(mcVersion.launcherFriendlyVersionName()));
	}

	// From Fabric-loom
	private static final Pattern MC_LV_PATTERN = Pattern.compile("\\$\\$\\d+");

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws Exception {
		Path rootPath = getInternalArtifactPath(mcVersion, mappingFlavour);

		if (mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) >= 0) {
			Path remappedPath = rootPath.resolve("%s-merged.jar".formatted(mappingFlavour.toString()));
			Path mergedPath = pipelineCache.getForKey(Step.STEP_MERGE_OBFUSCATED);
			return remap(mcVersion, "merged", mergedPath, remappedPath, mappingFlavour);
		} else {
			StepResult clientResult = null;
			StepResult serverResult = null;
			Path artifactRootPath = pipelineCache.getForKey(Step.STEP_FETCH_ARTIFACTS);
			if (mcVersion.hasClientCode()) {
				Path remappedClientPath = rootPath.resolve("%s-client.jar".formatted(mappingFlavour.toString()));
				Path clientPath = mcVersion.clientJar().resolve(artifactRootPath);
				clientResult = remap(mcVersion, "client", clientPath, remappedClientPath, mappingFlavour);
			}
			if (mcVersion.hasServerCode()) {
				Path remappedServerPath = rootPath.resolve("%s-server.jar".formatted(mappingFlavour.toString()));
				Path serverPath = mcVersion.serverJar().resolve(artifactRootPath);
				serverResult = remap(mcVersion, "server", serverPath, remappedServerPath, mappingFlavour);
			}
			return StepResult.merge(clientResult, serverResult);
		}
	}

	private StepResult remap(OrderedVersion mcVersion, String env, Path input, Path output, MappingFlavour mappingFlavour) throws IOException {
		if (Files.exists(output) && Files.size(output) > 22 /* not empty jar */) {
			return StepResult.UP_TO_DATE;
		}
		if (Files.exists(output)) {
			Files.delete(output);
		}
		if (input == null) {
			MiscHelper.panic("A %s for remapping JAR for version %s does not exist", env, mcVersion.launcherFriendlyVersionName());
		}
		TinyRemapper.Builder remapperBuilder = TinyRemapper.newRemapper()
				.renameInvalidLocals(true)
				.rebuildSourceFilenames(true)
				.invalidLvNamePattern(MC_LV_PATTERN)
				.inferNameFromSameLvIndex(true)
				.withMappings(mappingFlavour.getMappingImpl().getMappingsProvider(mcVersion))
				.fixPackageAccess(true)
				.threads(GitCraft.config.remappingThreads);
		TinyRemapper remapper = remapperBuilder.build();
		remapper.readInputs(input);
		try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
			remapper.apply(consumer, remapper.createInputTag());
		}
		remapper.finish();

		return StepResult.SUCCESS;
	}
}
