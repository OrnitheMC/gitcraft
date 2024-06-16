package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.meta.OrnitheNestsVersionMeta;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import com.github.winplay02.gitcraft.util.SerializationHelper;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
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

public class ApplyNestsStep extends Step {

	private final Path rootPath;

	public ApplyNestsStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public ApplyNestsStep() {
		this(GitCraftPaths.REMAPPED);
	}

	@Override
	public String getName() {
		return Step.STEP_APPLY_NESTS;
	}

	@Override
	public Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return rootPath.resolve(mcVersion.launcherFriendlyVersionName()).resolve(String.format("%s-nested.jar", mappingFlavour.toString()));
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

		Path input = pipelineCache.getForKey(Step.STEP_MERGE_MAPPED);
		if (input == null) {
			input = pipelineCache.getForKey(Step.STEP_REMAP);
		}
		Optional<Path> nestsPath = getNestsPath(mcVersion, mappingFlavour);

		if (nestsPath.isPresent()) {
			Nests nests = Nests.of(nestsPath.get());
			Nester.nestJar(new net.ornithemc.nester.Nester.Options(), input, nestedPath, nests);
		} else {
			Files.copy(input, nestedPath);
		}

		return StepResult.SUCCESS;
	}

	public static final String ORNITHE_NESTS_META = "https://meta.ornithemc.net/v3/versions/nests";

	private Map<String, OrnitheNestsVersionMeta> nestsVersions = null;

	private Optional<Path> getNestsPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) throws Exception {
		if (mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) >= 0) {
			OrnitheNestsVersionMeta nestsBuild = getLatestNestsBuild(mcVersion, Side.MERGED);
			return Optional.of(prepareNests(nestsBuild, mcVersion, Side.MERGED, mappingFlavour));
		} else {
			OrnitheNestsVersionMeta clientNestsBuild = getLatestNestsBuild(mcVersion, Side.CLIENT);
			OrnitheNestsVersionMeta serverNestsBuild = getLatestNestsBuild(mcVersion, Side.SERVER);
			if (clientNestsBuild == null && serverNestsBuild == null) {
				return Optional.empty();
			}
			Path clientNests = prepareNests(clientNestsBuild, mcVersion, Side.CLIENT, mappingFlavour);
			Path serverNests = prepareNests(serverNestsBuild, mcVersion, Side.SERVER, mappingFlavour);
			if (clientNests == null) {
				return Optional.of(serverNests);
			}
			if (serverNests == null) {
				return Optional.of(clientNests);
			}
			Path mergedNests = getNestsPath(clientNestsBuild, serverNestsBuild, mappingFlavour);
			if (!Files.exists(mergedNests)) {
				MappingUtils.mergeNests(clientNests, serverNests, mergedNests);
			}
			return Optional.of(mergedNests);
		}
	}

	public OrnitheNestsVersionMeta getLatestNestsBuild(OrderedVersion mcVersion, Side side) {
		if (nestsVersions == null) {
			try {
				List<OrnitheNestsVersionMeta> nestsVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_NESTS_META)), SerializationHelper.TYPE_LIST_ORNITHE_NESTS_VERSION_META);
				nestsVersions = nestsVersionMetas.stream().collect(Collectors.groupingBy(OrnitheNestsVersionMeta::gameVersion)).values().stream().map(ornitheNestsVersionMetas -> ornitheNestsVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheNestsVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return nestsVersions.get(mcVersion.launcherFriendlyVersionName() + side.suffix());
	}

	private Path prepareNests(OrnitheNestsVersionMeta nestsMeta, OrderedVersion mcVersion, Side side, MappingFlavour mappingFlavour) {
		if (nestsMeta == null) {
			return null;
		}
		Path nestsFile = getNestsPath(nestsMeta);
		if (Files.exists(nestsFile)) {
			return nestsFile;
		}
		Path nestsJar = getNestsJar(nestsMeta);
		try {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(nestsMeta.makeMavenURL(), new RemoteHelper.LocalFileInfo(nestsJar, null, "nests", nestsMeta.version()));
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(nestsJar)) {
				Path nestsPathInJar = fs.get().getPath("nests", "mappings.nest");
				Files.copy(nestsPathInJar, nestsFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
		Path mappedNestsFile = getNestsPath(nestsMeta, mappingFlavour);
		if (Files.exists(mappedNestsFile)) {
			return mappedNestsFile;
		}
		Optional<Path> mappings = mappingFlavour.getMappingImpl().getMappingsPath(mcVersion);
		if (!mappings.isPresent()) {
			MiscHelper.panic("could not get %s mappings for %s", mappingFlavour.toString(), mcVersion);
		}
		Path mappingsPath = mappings.get();
		try {
			MemoryMappingTree mappingTree = new MemoryMappingTree();
			MappingVisitor visitor = new MappingDstNsReorder(mappingTree, mappingFlavour.getMappingImpl().getDestinationNS());
			if (side != Side.MERGED) {
				visitor = new MappingSourceNsSwitch(visitor, "official", true);
				visitor = new MappingNsRenamer(visitor, Map.of(side == Side.CLIENT ? "clientOfficial" : "serverOfficial", "official"));
			}
			MappingReader.read(mappingsPath, visitor);

			mappingsPath = mappingsPath.resolveSibling("%s%s-%s.tmp.tiny".formatted(mcVersion.launcherFriendlyVersionName(), side.suffix(), mappingFlavour.toString()));

			Files.deleteIfExists(mappingsPath);
			try (MappingWriter writer = MappingWriter.create(mappingsPath, MappingFormat.TINY_2_FILE)) {
				mappingTree.accept(writer);
			}
			MappingUtils.mapNests(nestsFile, mappedNestsFile, Format.TINY_V2, mappingsPath);

			Files.delete(mappingsPath);
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
		return mappedNestsFile;
	}

	private Path getNestsJar(OrnitheNestsVersionMeta meta) {
		return GitCraftPaths.NESTS.resolve("%s-nests+build.%s.jar".formatted(meta.gameVersion(), meta.build()));
	}

	private Path getNestsPath(OrnitheNestsVersionMeta meta) {
		return GitCraftPaths.NESTS.resolve("%s-nests+build.%d.nest".formatted(meta.gameVersion(), meta.build()));
	}

	private Path getNestsPath(OrnitheNestsVersionMeta meta, MappingFlavour mappingFlavour) {
		return GitCraftPaths.NESTS.resolve("%s-nests+build.%d-%s.nest".formatted(meta.gameVersion(), meta.build(), mappingFlavour.toString()));
	}

	private Path getNestsPath(OrnitheNestsVersionMeta clientMeta, OrnitheNestsVersionMeta serverMeta, MappingFlavour mappingFlavour) {
		return GitCraftPaths.NESTS.resolve("%s-nests+build.(%d-%d)-%s.nest".formatted(clientMeta.gameVersion(), clientMeta.build(), serverMeta.build(), mappingFlavour.toString()));
	}

	private enum Side {

		CLIENT("-client"), SERVER("-server"), MERGED("");

		private final String suffix;

		private Side(String suffix) {
			this.suffix = suffix;
		}

		public String suffix() {
			return suffix;
		}
	}
}
