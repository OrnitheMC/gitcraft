package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.meta.OrnitheRavenVersionMeta;
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
import net.ornithemc.exceptor.Exceptor;
import net.ornithemc.mappingutils.MappingUtils;
import net.ornithemc.mappingutils.io.Format;

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

public class ApplyExceptionsStep extends Step {

	private final Path rootPath;

	public ApplyExceptionsStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public ApplyExceptionsStep() {
		this(GitCraftPaths.REMAPPED);
	}

	@Override
	public String getName() {
		return Step.STEP_APPLY_EXCEPTIONS;
	}

	@Override
	public Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "exceptions");
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws Exception {
		Path patchedPath = getInternalArtifactPath(mcVersion, mappingFlavour);
		if (Files.exists(patchedPath) && Files.size(patchedPath) > 22 /* not empty jar */) {
			return StepResult.UP_TO_DATE;
		}
		if (Files.exists(patchedPath)) {
			Files.delete(patchedPath);
		}

		Path input = pipelineCache.getForKey(Step.STEP_MERGE_MAPPED);
		if (input == null) {
			input = pipelineCache.getForKey(Step.STEP_APPLY_EXCEPTIONS);
			if (input == null) {
				input = pipelineCache.getForKey(Step.STEP_MERGE_MAPPED);
				if (input == null) {
					input = RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "merged");
				}
			}
		}
		Optional<Path> exceptionsPath = getExceptionsPath(mcVersion, mappingFlavour);

		if (exceptionsPath.isPresent()) {
			Files.copy(input, patchedPath);
			Exceptor.apply(patchedPath, exceptionsPath.get());

			return StepResult.SUCCESS;
		} else {
			return StepResult.NOT_RUN;
		}
	}

	public static final String ORNITHE_RAVEN_META = "https://meta.ornithemc.net/v3/versions/raven";

	private Map<String, OrnitheRavenVersionMeta> ravenVersions = null;

	private Optional<Path> getExceptionsPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) throws Exception {
		if (mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) >= 0) {
			OrnitheRavenVersionMeta ravenBuild = getLatestRavenBuild(mcVersion, Side.MERGED);
			return Optional.ofNullable(prepareExceptions(ravenBuild, mcVersion, Side.MERGED, mappingFlavour));
		} else {
			OrnitheRavenVersionMeta clientRavenBuild = getLatestRavenBuild(mcVersion, Side.CLIENT);
			OrnitheRavenVersionMeta serverRavenBuild = getLatestRavenBuild(mcVersion, Side.SERVER);
			if (clientRavenBuild == null && serverRavenBuild == null) {
				return Optional.empty();
			}
			Path clientExceptions = prepareExceptions(clientRavenBuild, mcVersion, Side.CLIENT, mappingFlavour);
			Path serverExceptions = prepareExceptions(serverRavenBuild, mcVersion, Side.SERVER, mappingFlavour);
			if (clientExceptions == null) {
				return Optional.of(serverExceptions);
			}
			if (serverExceptions == null) {
				return Optional.of(clientExceptions);
			}
			Path mergedExceptions = getRavenPath(clientRavenBuild, serverRavenBuild, mappingFlavour);
			if (!Files.exists(mergedExceptions)) {
				MappingUtils.mergeExceptions(clientExceptions, serverExceptions, mergedExceptions);
			}
			return Optional.of(mergedExceptions);
		}
	}

	public OrnitheRavenVersionMeta getLatestRavenBuild(OrderedVersion mcVersion, Side side) {
		if (ravenVersions == null) {
			try {
				List<OrnitheRavenVersionMeta> ravenVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_RAVEN_META)), SerializationHelper.TYPE_LIST_ORNITHE_RAVEN_VERSION_META);
				ravenVersions = ravenVersionMetas.stream().collect(Collectors.groupingBy(OrnitheRavenVersionMeta::gameVersion)).values().stream().map(OrnitheRavenVersionMetas -> OrnitheRavenVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheRavenVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return ravenVersions.get(mcVersion.launcherFriendlyVersionName() + side.suffix());
	}

	private Path prepareExceptions(OrnitheRavenVersionMeta ravenMeta, OrderedVersion mcVersion, Side side, MappingFlavour mappingFlavour) {
		if (ravenMeta == null) {
			return null;
		}
		Path ravenFile = getRavenPath(ravenMeta);
		if (Files.exists(ravenFile)) {
			return ravenFile;
		}
		Path ravenJar = getRavenJar(ravenMeta);
		try {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(ravenMeta.makeMavenURL(), new RemoteHelper.LocalFileInfo(ravenJar, null, "exceptions", ravenMeta.version()));
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(ravenJar)) {
				Path exceptionsPathInJar = fs.get().getPath("exceptions", "mappings.excs");
				Files.copy(exceptionsPathInJar, ravenFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
		Path mappedRavenFile = getRavenPath(ravenMeta, mappingFlavour);
		if (Files.exists(mappedRavenFile)) {
			return mappedRavenFile;
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
			MappingUtils.mapExceptions(ravenFile, mappedRavenFile, Format.TINY_V2, mappingsPath);

			Files.delete(mappingsPath);
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
		return mappedRavenFile;
	}

	private Path getRavenJar(OrnitheRavenVersionMeta meta) {
		return GitCraftPaths.RAVEN.resolve("%s-raven+build.%s.jar".formatted(meta.gameVersion(), meta.build()));
	}

	private Path getRavenPath(OrnitheRavenVersionMeta meta) {
		return GitCraftPaths.RAVEN.resolve("%s-raven+build.%d.excs".formatted(meta.gameVersion(), meta.build()));
	}

	private Path getRavenPath(OrnitheRavenVersionMeta meta, MappingFlavour mappingFlavour) {
		return GitCraftPaths.RAVEN.resolve("%s-raven+build.%d-%s.excs".formatted(meta.gameVersion(), meta.build(), mappingFlavour.toString()));
	}

	private Path getRavenPath(OrnitheRavenVersionMeta clientMeta, OrnitheRavenVersionMeta serverMeta, MappingFlavour mappingFlavour) {
		return GitCraftPaths.RAVEN.resolve("%s-raven+build.(%d-%d)-%s.excs".formatted(clientMeta.gameVersion(), clientMeta.build(), serverMeta.build(), mappingFlavour.toString()));
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
