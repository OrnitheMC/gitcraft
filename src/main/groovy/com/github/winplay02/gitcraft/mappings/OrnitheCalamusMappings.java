package com.github.winplay02.gitcraft.mappings;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.pipeline.Step;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.tiny.Tiny1FileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class OrnitheCalamusMappings extends Mapping {
	@Override
	public String getName() {
		return "Calamus";
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
		Path mappingsFile = getMappingsPathInternal(mcVersion);
		if (Files.exists(mappingsFile) && validateMappings(mappingsFile)) {
			return Step.StepResult.UP_TO_DATE;
		}
		Files.deleteIfExists(mappingsFile);
		Path mappingsV1 = getMappingsPathInternalV1(mcVersion);
		Step.StepResult downloadResult = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryGitHub("OrnitheMC/calamus", "gen%d".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN), String.format("mappings/%s.tiny", mcVersion.launcherFriendlyVersionName()), new RemoteHelper.LocalFileInfo(mappingsV1, null, "intermediary mapping", mcVersion.launcherFriendlyVersionName()));
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		try (BufferedReader br = Files.newBufferedReader(mappingsV1, StandardCharsets.UTF_8)) {
			Tiny1FileReader.read(br, mappingTree);
		}
		try (MappingWriter w = MappingWriter.create(mappingsFile, MappingFormat.TINY_2_FILE)) {
			mappingTree.accept(w);
		}
		return Step.StepResult.merge(downloadResult, Step.StepResult.SUCCESS);
	}

	protected Path getMappingsPathInternalV1(OrderedVersion mcVersion) {
		return GitCraftPaths.MAPPINGS.resolve(mcVersion.launcherFriendlyVersionName() + "-calamus-intermediary-gen%d-v1.tiny".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN));
	}

	@Override
	protected Path getMappingsPathInternal(OrderedVersion mcVersion) {
		return GitCraftPaths.MAPPINGS.resolve(mcVersion.launcherFriendlyVersionName() + "-calamus-intermediary-gen%d.tiny".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN));
	}
}
