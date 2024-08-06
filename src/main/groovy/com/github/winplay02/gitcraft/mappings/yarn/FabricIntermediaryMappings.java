package com.github.winplay02.gitcraft.mappings.yarn;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.mappings.Mapping;
import com.github.winplay02.gitcraft.pipeline.MinecraftJar;
import com.github.winplay02.gitcraft.pipeline.StepStatus;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.tiny.Tiny1FileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricIntermediaryMappings extends Mapping {
	@Override
	public String getName() {
		return "Fabric Intermediary";
	}

	@Override
	public String getDestinationNS() {
		return MappingsNamespace.INTERMEDIARY.toString();
	}

	@Override
	public boolean doMappingsExist(OrderedVersion mcVersion) {
		if (GitCraftConfig.intermediaryMissingVersions.contains(mcVersion.launcherFriendlyVersionName())) {
			return false;
		}
		return mcVersion.compareTo(GitCraft.config.manifestSource.getMetadataProvider().getVersionByVersionID(GitCraftConfig.FABRIC_INTERMEDIARY_MAPPINGS_START_VERSION_ID)) >= 0;
	}

	@Override
	public boolean doMappingsExist(OrderedVersion mcVersion, MinecraftJar minecraftJar) {
		// fabric intermediary is provided for the merged jar
		return minecraftJar == MinecraftJar.MERGED && doMappingsExist(mcVersion);
	}

	@Override
	public boolean canMappingsBeUsedOn(OrderedVersion mcVersion, MinecraftJar minecraftJar) {
		// the merged mappings can be used for all jars
		return true;
	}

	protected static String mappingsIntermediaryPathQuirkVersion(String version) {
		return GitCraftConfig.yarnInconsistentVersionNaming.getOrDefault(version, version);
	}

	@Override
	public StepStatus provideMappings(OrderedVersion mcVersion, MinecraftJar minecraftJar) throws IOException {
		// fabric intermediary is provided for the merged jar
		if (minecraftJar != MinecraftJar.MERGED) {
			return StepStatus.NOT_RUN;
		}
		Path mappingsFile = getMappingsPathInternal(mcVersion, minecraftJar);
		if (Files.exists(mappingsFile) && validateMappings(mappingsFile)) {
			return StepStatus.UP_TO_DATE;
		}
		Files.deleteIfExists(mappingsFile);
		Path mappingsV1 = getMappingsPathInternalV1(mcVersion);
		StepStatus downloadStatus = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryGitHub("FabricMC/intermediary", "master", String.format("mappings/%s.tiny", mappingsIntermediaryPathQuirkVersion(mcVersion.launcherFriendlyVersionName())), new RemoteHelper.LocalFileInfo(mappingsV1, null, "intermediary mapping", mcVersion.launcherFriendlyVersionName()));
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		try (BufferedReader br = Files.newBufferedReader(mappingsV1, StandardCharsets.UTF_8)) {
			Tiny1FileReader.read(br, mappingTree);
		}
		try (MappingWriter writer = MappingWriter.create(mappingsFile, MappingFormat.TINY_2_FILE)) {
			mappingTree.accept(writer);
		}
		return StepStatus.merge(downloadStatus, StepStatus.SUCCESS);
	}

	protected Path getMappingsPathInternalV1(OrderedVersion mcVersion) {
		return GitCraftPaths.MAPPINGS.resolve(mcVersion.launcherFriendlyVersionName() + "-intermediary-v1.tiny");
	}

	@Override
	protected Path getMappingsPathInternal(OrderedVersion mcVersion, MinecraftJar minecraftJar) {
		return GitCraftPaths.MAPPINGS.resolve(mcVersion.launcherFriendlyVersionName() + "-intermediary.tiny");
	}

	@Override
	public void visit(OrderedVersion mcVersion, MinecraftJar minecraftJar, MappingVisitor visitor) throws IOException {
		Path path = getMappingsPathInternal(mcVersion, MinecraftJar.MERGED);
		try (BufferedReader br = Files.newBufferedReader(path)) {
			Tiny2FileReader.read(br, visitor);
		}
	}
}