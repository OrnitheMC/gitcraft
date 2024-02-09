package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.FeatherMappings;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.meta.OrnitheNestsVersionMeta;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import com.github.winplay02.gitcraft.util.SerializationHelper;
import net.fabricmc.loom.util.FileSystemUtil;
import net.ornithemc.mappingutils.MappingUtils;
import net.ornithemc.mappingutils.io.Format;
import net.ornithemc.nester.Nester;
import net.ornithemc.nester.nest.Nests;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class NestsStep extends Step {
	public NestsStep() {}

	@Override
	public String getName() {
		return Step.STEP_NESTS;
	}

	@Override
	public Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return GitCraftPaths.REMAPPED.resolve(String.format("%s-%s-nested.jar", mcVersion.launcherFriendlyVersionName(), mappingFlavour.toString()));
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws Exception {
		Path nestedPath = getInternalArtifactPath(mcVersion, mappingFlavour);
		if (Files.exists(nestedPath) && Files.size(nestedPath) > 22 /* not empty jar */) {
			return StepResult.UP_TO_DATE;
		}
		if (Files.exists(nestedPath)) {
			Files.delete(nestedPath);
		}

		Path input = pipelineCache.getForKey(Step.STEP_REMAP);
		Optional<Path> nestsPath = getNestsPath(mcVersion, mappingFlavour);

		if (nestsPath.isPresent()) {
			Nests nests = Nests.of(nestsPath.get());
			Nester.nestJar(new net.ornithemc.nester.Nester.Options(), input, nestedPath, nests);
		} else {
			Files.copy(input, nestedPath);
		}

		return StepResult.SUCCESS;
	}

	public static Optional<Path> getNestsPath(OrderedVersion mcVersion) {
		return Optional.ofNullable(nestsPath(mcVersion));
	}

	public static Optional<Path> getNestsPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return Optional.ofNullable(nestsPath(mcVersion, mappingFlavour));
	}

	public static final String ORNITHE_NESTS_META = "https://meta.ornithemc.net/v3/versions/nests";

	private static Map<String, OrnitheNestsVersionMeta> nestsVersions = null;

	public static OrnitheNestsVersionMeta getNestsLatestBuild(OrderedVersion mcVersion) {
		if (nestsVersions == null) {
			try {
				List<OrnitheNestsVersionMeta> nestsVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_NESTS_META)), SerializationHelper.TYPE_LIST_ORNITHE_NESTS_VERSION_META);
				nestsVersions = nestsVersionMetas.stream().collect(Collectors.groupingBy(OrnitheNestsVersionMeta::gameVersion)).values().stream().map(ornitheNestsVersionMetas -> ornitheNestsVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheNestsVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return nestsVersions.get(mcVersion.launcherFriendlyVersionName());
	}

	private static Path nestsPath(OrderedVersion mcVersion) {
		OrnitheNestsVersionMeta nestsVersion = getNestsLatestBuild(mcVersion);
		if (nestsVersion == null) {
			return null;
		}
		Path nestsFile = GitCraftPaths.NESTS.resolve(String.format("%s-nests-build.%s.nest", mcVersion.launcherFriendlyVersionName(), nestsVersion.build()));
		if (nestsFile.toFile().exists()) {
			return nestsFile;
		}
		Path nestsFileJar = GitCraftPaths.NESTS.resolve(String.format("%s-nests-build.%s.jar", mcVersion.launcherFriendlyVersionName(), nestsVersion.build()));
		try {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(nestsVersion.makeMavenURL(), new RemoteHelper.LocalFileInfo(nestsFileJar, null, "nests", mcVersion.launcherFriendlyVersionName()));
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(nestsFileJar)) {
				Path nestsPathInJar = fs.get().getPath("nests", "mappings.nest");
				Files.copy(nestsPathInJar, nestsFile, StandardCopyOption.REPLACE_EXISTING);
			}
			return nestsFile;
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
	}

	private static Path nestsPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		OrnitheNestsVersionMeta nestsVersion = getNestsLatestBuild(mcVersion);
		if (nestsVersion == null) {
			return null;
		}
		Path nestsFile = nestsPath(mcVersion);
		Path mappedNestsFile = GitCraftPaths.NESTS.resolve(String.format("%s-nests-build.%s-%s.nest", mcVersion.launcherFriendlyVersionName(), nestsVersion.build(), mappingFlavour.toString()));
		if (mappedNestsFile.toFile().exists()) {
			return mappedNestsFile;
		}
		try {
			MappingUtils.mapNests(nestsFile, mappedNestsFile, Format.TINY_V2, FeatherMappings.getSimplifiedMappingsPath(mcVersion));
			return mappedNestsFile;
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
	}
}
