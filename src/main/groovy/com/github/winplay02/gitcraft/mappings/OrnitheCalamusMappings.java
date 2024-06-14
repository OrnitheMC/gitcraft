package com.github.winplay02.gitcraft.mappings;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.pipeline.Step;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.nio.file.Path;

public class OrnitheCalamusMappings extends Mapping {
	@Override
	public String getName() {
		return null;
	}

	@Override
	public String getDestinationNS() {
		return MappingsNamespace.INTERMEDIARY.toString();
	}

	@Override
	public boolean doMappingsExist(OrderedVersion mcVersion) {
		return mcVersion.compareTo(GitCraftConfig.CALAMUS_MAPPINGS_START_VERSION) >= 0
			&& mcVersion.compareTo(GitCraftConfig.CALAMUS_MAPPINGS_END_VERSION) <= 0;
	}

	@Override
	public Step.StepResult prepareMappings(OrderedVersion mcVersion) throws IOException {
		return null;
	}

	@Override
	protected Path getMappingsPathInternal(OrderedVersion mcVersion) {
		return mappingsPathCalamus(mcVersion);
	}

	public Path getSimplifiedMappingsPath(OrderedVersion mcVersion) {
		return mappingsPathCalamus(mcVersion);
	}

	private static Path mappingsPathCalamus(OrderedVersion mcVersion) {
		if (mcVersion.semanticVersion().compareTo(String.valueOf(GitCraftConfig.CALAMUS_MAPPINGS_START_VERSION)) < 0) {
			return null;
		}
		if (mcVersion.semanticVersion().compareTo(String.valueOf(GitCraftConfig.CALAMUS_MAPPINGS_END_VERSION)) > 0) {
			return null;
		}
		Path mappingsFile = GitCraftPaths.MAPPINGS.resolve(mcVersion.launcherFriendlyVersionName() + "-calamus-intermediary-gen%d.tiny".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN));
		if (!mappingsFile.toFile().exists()) {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryGitHub("OrnitheMC/calamus", "gen%d".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN), String.format("mappings/%s.tiny", mcVersion.launcherFriendlyVersionName()), new RemoteHelper.LocalFileInfo(mappingsFile, null, "intermediary mapping", mcVersion.launcherFriendlyVersionName()));
		}
		return mappingsFile;
	}

	public static MemoryMappingTree createIntermediaryMappingsProvider(OrderedVersion mcVersion) throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		Path intermediaryPath = mappingsPathCalamus(mcVersion);
		if (intermediaryPath != null) {
			MappingReader.read(intermediaryPath, mappingTree);
		}
		return mappingTree;
	}
}
