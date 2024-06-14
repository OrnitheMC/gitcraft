package com.github.winplay02.gitcraft.mappings;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.meta.OrnitheFeatherVersionMeta;
import com.github.winplay02.gitcraft.pipeline.Step;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.SerializationHelper;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FeatherMappings extends Mapping {

	public static final String ORNITHE_FEATHER_META = "https://meta.ornithemc.net/v3/versions/gen%d/feather".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN);
	private static Map<String, OrnitheFeatherVersionMeta> featherVersions = null;
	protected OrnitheCalamusMappings intermediaryMappings;

	public FeatherMappings(OrnitheCalamusMappings ornitheCalamus) {
		intermediaryMappings = ornitheCalamus;
	}

	@Override
	public String getName() {
		return "Feather";
	}

	@Override
	public String getDestinationNS() {
		return MappingsNamespace.NAMED.toString();
	}

	@Override
	public boolean doMappingsExist(OrderedVersion mcVersion) {
		initFeatherVersions();
        if (mcVersion.compareTo(GitCraftConfig.FEATHER_MAPPINGS_START_VERSION) >= 0
                && mcVersion.compareTo(GitCraftConfig.FEATHER_MAPPINGS_END_VERSION) <= 0) {
			return featherVersions.containsKey(mcVersion.launcherFriendlyVersionName());
		} else {
			return false;
		}
	}

	@Override
	public Step.StepResult prepareMappings(OrderedVersion mcVersion) throws IOException {
		Path mappingsFile = getMappingsPathInternal(mcVersion);
		// Try existing
		if (Files.exists(mappingsFile)) {
			return Step.StepResult.UP_TO_DATE;
		}
		OrnitheFeatherVersionMeta featherVersion = getFeatherLatestBuild(mcVersion);
		if (featherVersion == null) {
			// MiscHelper.panic("Tried to use feather for version %s. Feather mappings do not exist for this version.", mcVersion.version);
			MiscHelper.println("Tried to use feather gen %d for version %s. Feather mappings do not exist for this version in meta.ornithemc.net. Falling back to generated version...", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, mcVersion.launcherFriendlyVersionName());
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.launcherFriendlyVersionName(), "+build.", 1, String.format("net.ornithemc:feather-gen%d:%s+build.%s:unknown-fallback", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, mcVersion.launcherFriendlyVersionName(), 1), String.format("%s+build.%s", mcVersion.launcherFriendlyVersionName(), 1), !mcVersion.isSnapshot());
		}
		Path mappingsFileJar = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-gen%d-build.%s.jar", mcVersion.launcherFriendlyVersionName(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, featherVersion.build()));
		try {
			Step.StepResult result = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(featherVersion.makeMavenURLMergedV2(), new RemoteHelper.LocalFileInfo(mappingsFileJar, null, "feather mapping", mcVersion.launcherFriendlyVersionName()));
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileJar)) {
				Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
				Files.copy(mappingsPathInJar, mappingsFile, StandardCopyOption.REPLACE_EXISTING);
			}
			return Step.StepResult.merge(result, Step.StepResult.SUCCESS);
		} catch (IOException | RuntimeException e) {
			throw new IOException("unable to find Feather gen %d mergedv2 mappings for Minecraft version %s".formatted(GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, mcVersion), e);
		}
	}

	@Override
	protected Path getMappingsPathInternal(OrderedVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getTargetFeatherBuild(mcVersion);
		return GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-gen%d-build.%s.tiny", mcVersion.launcherFriendlyVersionName(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, featherVersion.build()));
	}

	private static OrnitheFeatherVersionMeta getTargetFeatherBuild(OrderedVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getFeatherLatestBuild(mcVersion);
		if (featherVersion == null) {
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.launcherFriendlyVersionName(), "+build.", 1, String.format("net.ornithemc:feather-gen%d:%s+build.%s:unknown-fallback", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, mcVersion.launcherFriendlyVersionName(), 1), String.format("%s+build.%s", mcVersion.launcherFriendlyVersionName(), 1), !mcVersion.isSnapshotOrPending());
		}
		return featherVersion;
	}

	public static Path getSimplifiedMappingsPath(OrderedVersion mcVersion) {
		return mappingsPathFeatherSimplified(mcVersion);
	}

	public static void initFeatherVersions() {
		if (featherVersions == null) {
			try {
				List<OrnitheFeatherVersionMeta> featherVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_FEATHER_META)), SerializationHelper.TYPE_LIST_ORNITHE_FEATHER_VERSION_META);
				featherVersions = featherVersionMetas.stream().collect(Collectors.groupingBy(OrnitheFeatherVersionMeta::gameVersion)).values().stream().map(ornitheFeatherVersionMetas -> ornitheFeatherVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheFeatherVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static OrnitheFeatherVersionMeta getFeatherLatestBuild(OrderedVersion mcVersion) {
		initFeatherVersions();
		return featherVersions.get(mcVersion.launcherFriendlyVersionName());
	}

	private static Path mappingsPathFeatherSimplified(OrderedVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getTargetFeatherBuild(mcVersion);
		Path mappingsFile = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-gen%d-build.%s.tiny", mcVersion.launcherFriendlyVersionName(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, featherVersion.build()));

		if (featherVersion == null) {
			// MiscHelper.panic("Tried to use feather for version %s. Feather mappings do not exist for this version.", mcVersion.version);
			MiscHelper.println("Tried to use feather gen%d for version %s. Feather mappings do not exist for this version in meta.ornithemc.net. Falling back to generated version...", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, mcVersion.launcherFriendlyVersionName());
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.launcherFriendlyVersionName(), "+build.", 1, String.format("net.ornithemc:feather-gen%d:%s+build.%s:unknown-fallback", GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, mcVersion.launcherFriendlyVersionName(), 1), String.format("%s+build.%s", mcVersion.launcherFriendlyVersionName(), 1), !mcVersion.isSnapshot());
		}
		Path mappingsSimplifiedFile = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-gen%d-build.%s-simplified.tiny", mcVersion.launcherFriendlyVersionName(), GitCraftConfig.ORNITHE_INTERMEDIARY_GEN, featherVersion.build()));
		if (mappingsSimplifiedFile.toFile().exists()) {
			return mappingsSimplifiedFile;
		}
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		try {
			MappingReader.read(mappingsFile, mappingTree);
			try (MappingWriter writer = MappingWriter.create(mappingsSimplifiedFile, MappingFormat.TINY_2_FILE)) {
				MappingDstNsReorder dstReorder = new MappingDstNsReorder(writer, List.of(MappingsNamespace.NAMED.toString()));
				mappingTree.accept(dstReorder);
			}
			return mappingsSimplifiedFile;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
