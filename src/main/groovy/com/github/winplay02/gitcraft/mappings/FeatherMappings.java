package com.github.winplay02.gitcraft.mappings;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.meta.OrnitheFeatherVersionMeta;
import com.github.winplay02.gitcraft.pipeline.Step;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.SerializationHelper;
import groovy.lang.Tuple2;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FeatherMappings extends Mapping {

	public static final String ORNITHE_FEATHER_META = "https://meta.ornithemc.net/v3/versions/feather";
	private static Map<String, OrnitheFeatherVersionMeta> featherVersions = null;
	protected OrnitheCalamus intermediaryMappings;

	public FeatherMappings(OrnitheCalamus ornitheCalamus) {
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
			MiscHelper.println("Tried to use feather for version %s. Feather mappings do not exist for this version in meta.ornithemc.net. Falling back to generated version...", mcVersion.launcherFriendlyVersionName());
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.launcherFriendlyVersionName(), "+build.", 1, String.format("net.ornithemc:feather:%s+build.%s:unknown-fallback", mcVersion.launcherFriendlyVersionName(), 1), String.format("%s+build.%s", mcVersion.launcherFriendlyVersionName(), 1), !mcVersion.isSnapshot());
		}
		{
			Path mappingsFileJar = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-build.%s.jar", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
			try {
				Step.StepResult result = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(featherVersion.makeMavenURLMergedV2(), new RemoteHelper.LocalFileInfo(mappingsFileJar, null, "feather mapping", mcVersion.launcherFriendlyVersionName()));
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileJar)) {
					Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
					Files.copy(mappingsPathInJar, mappingsFile, StandardCopyOption.REPLACE_EXISTING);
				}
				return Step.StepResult.merge(result, Step.StepResult.SUCCESS);
			} catch (IOException | RuntimeException ignored) {
				Files.deleteIfExists(mappingsFileJar);
			}
			MiscHelper.println("Merged feather mappings do not exist for %s, merging with intermediary ourselves...", mcVersion.launcherFriendlyVersionName());
		}
		{
			Tuple2<Path, Step.StepResult> mappingsFileUnmerged = mappingsPathFeatherUnmerged(mcVersion, featherVersion);
			Step.StepResult intermediaryResult = intermediaryMappings.prepareMappings(mcVersion);
			Path mappingsFileIntermediary = intermediaryMappings.getMappingsPathInternal(mcVersion);
			MemoryMappingTree mappingTree = new MemoryMappingTree();
			// Intermediary first
			MappingSourceNsSwitch nsSwitchIntermediary = new MappingSourceNsSwitch(mappingTree, MappingsNamespace.INTERMEDIARY.toString());
			MappingReader.read(mappingsFileIntermediary, nsSwitchIntermediary);
			// Then named yarn
			MappingSourceNsSwitch nsSwitchYarn = new MappingSourceNsSwitch(mappingTree, MappingsNamespace.INTERMEDIARY.toString());
			MappingReader.read(mappingsFileUnmerged.getV1(), nsSwitchYarn);
			yarn_fixInnerClasses(mappingTree);
			try (MappingWriter writer = MappingWriter.create(mappingsFile, MappingFormat.TINY_2_FILE)) {
				MappingNsCompleter nsCompleter = new MappingNsCompleter(writer, Map.of(MappingsNamespace.NAMED.toString(), MappingsNamespace.INTERMEDIARY.toString()), true);
				MappingDstNsReorder dstReorder = new MappingDstNsReorder(nsCompleter, List.of(MappingsNamespace.INTERMEDIARY.toString(), MappingsNamespace.NAMED.toString()));
				MappingSourceNsSwitch sourceNsSwitch = new MappingSourceNsSwitch(dstReorder, MappingsNamespace.OFFICIAL.toString());
				mappingTree.accept(sourceNsSwitch);
			}
			return Step.StepResult.merge(mappingsFileUnmerged.getV2(), intermediaryResult, Step.StepResult.SUCCESS);
		}
	}

	@Override
	protected Path getMappingsPathInternal(OrderedVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getTargetFeatherBuild(mcVersion);
		return GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-build.%s.tiny", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
	}

	private static OrnitheFeatherVersionMeta getTargetFeatherBuild(OrderedVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getFeatherLatestBuild(mcVersion);
		if (featherVersion == null) {
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.launcherFriendlyVersionName(), "+build.", 1, String.format("net.ornithe:feather:%s+build.%s:unknown-fallback", mcVersion.launcherFriendlyVersionName(), 1), String.format("%s+build.%s", mcVersion.launcherFriendlyVersionName(), 1), !mcVersion.isSnapshotOrPending());
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
		Path mappingsFile = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-build.%s.tiny", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));

		if (featherVersion == null) {
			// MiscHelper.panic("Tried to use feather for version %s. Feather mappings do not exist for this version.", mcVersion.version);
			MiscHelper.println("Tried to use feather for version %s. Feather mappings do not exist for this version in meta.ornithemc.net. Falling back to generated version...", mcVersion.launcherFriendlyVersionName());
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.launcherFriendlyVersionName(), "+build.", 1, String.format("net.ornithemc:feather:%s+build.%s:unknown-fallback", mcVersion.launcherFriendlyVersionName(), 1), String.format("%s+build.%s", mcVersion.launcherFriendlyVersionName(), 1), !mcVersion.isSnapshot());
		}
		Path mappingsSimplifiedFile = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-build.%s-simplified.tiny", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
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

	private static void yarn_fixInnerClasses(MemoryMappingTree mappingTree) {
		int named = mappingTree.getNamespaceId(MappingsNamespace.NAMED.toString());

		for (MappingTree.ClassMapping entry : mappingTree.getClasses()) {
			String name = entry.getName(named);

			if (name != null) {
				continue;
			}

			entry.setDstName(yarn_matchEnclosingClass(entry.getSrcName(), mappingTree), named);
		}
	}

	private static String yarn_matchEnclosingClass(String sharedName, MemoryMappingTree mappingTree) {
		final int named = mappingTree.getNamespaceId(MappingsNamespace.NAMED.toString());
		final String[] path = sharedName.split(Pattern.quote("$"));

		for (int i = path.length - 2; i >= 0; i--) {
			final String currentPath = String.join("$", Arrays.copyOfRange(path, 0, i + 1));
			final MappingTree.ClassMapping match = mappingTree.getClass(currentPath);

			if (match != null && match.getName(named) != null) {
				return match.getName(named) + "$" + String.join("$", Arrays.copyOfRange(path, i + 1, path.length));
			}
		}

		return sharedName;
	}

	private static Tuple2<Path, Step.StepResult> mappingsPathFeatherUnmerged(OrderedVersion mcVersion, OrnitheFeatherVersionMeta featherVersion) {
		try {
			Path mappingsFileUnmerged = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s.tiny", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
			if (Files.exists(mappingsFileUnmerged)) {
				return Tuple2.tuple(mappingsFileUnmerged, Step.StepResult.UP_TO_DATE);
			}
			Path mappingsFileUnmergedJar = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s.jar", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
			Step.StepResult result = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(featherVersion.makeMavenURLUnmergedV2(), new RemoteHelper.LocalFileInfo(mappingsFileUnmergedJar, null, "unmerged feather mapping", mcVersion.launcherFriendlyVersionName()));
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileUnmergedJar)) {
				Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
				Files.copy(mappingsPathInJar, mappingsFileUnmerged, StandardCopyOption.REPLACE_EXISTING);
			}
			return Tuple2.tuple(mappingsFileUnmerged, result);
		} catch (IOException | RuntimeException e) {
			MiscHelper.println("Feather mappings in tiny-v2 format do not exist for %s, falling back to tiny-v1 mappings...", mcVersion.launcherFriendlyVersionName());
			try {
				Path mappingsFileUnmergedv1 = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s-v1.tiny", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
				if (Files.exists(mappingsFileUnmergedv1)) {
					return Tuple2.tuple(mappingsFileUnmergedv1, Step.StepResult.UP_TO_DATE);
				}
				Path mappingsFileUnmergedJarv1 = GitCraftPaths.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s-v1.jar", mcVersion.launcherFriendlyVersionName(), featherVersion.build()));
				Step.StepResult result = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(featherVersion.makeMavenURLUnmergedV1(), new RemoteHelper.LocalFileInfo(mappingsFileUnmergedJarv1, null, "unmerged feather mapping (v1 fallback)", mcVersion.launcherFriendlyVersionName()));
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileUnmergedJarv1)) {
					Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
					Files.copy(mappingsPathInJar, mappingsFileUnmergedv1, StandardCopyOption.REPLACE_EXISTING);
				}
				return Tuple2.tuple(mappingsFileUnmergedv1, result);
			} catch (IOException e2) {
				MiscHelper.println("Feather mappings for version %s cannot be fetched. Giving up after trying merged-v2, v2, and v1 mappings.", mcVersion.launcherFriendlyVersionName());
				throw new RuntimeException(e);
			}
		}
	}
}
