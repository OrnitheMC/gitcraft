package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.meta.OrnitheSparrowVersionMeta;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import com.github.winplay02.gitcraft.util.SerializationHelper;

import io.github.gaming32.signaturechanger.cli.ApplyAction;
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

public class ApplySignaturesStep extends Step {

	private final Path rootPath;

	public ApplySignaturesStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	public ApplySignaturesStep() {
		this(GitCraftPaths.REMAPPED);
	}

	@Override
	public String getName() {
		return Step.STEP_APPLY_SIGNATURES;
	}

	@Override
	public Path getInternalArtifactPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "signatures");
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

		Path input = pipelineCache.getForKey(Step.STEP_APPLY_EXCEPTIONS);
		if (input == null) {
			input = pipelineCache.getForKey(Step.STEP_MERGE_MAPPED);
			if (input == null) {
				input = RemapStep.getMappedJarPath(rootPath, mcVersion, mappingFlavour, "merged");
			}
		}
		Optional<Path> signaturesPath = getSignaturesPath(mcVersion, mappingFlavour);

		if (signaturesPath.isPresent()) {
			Files.copy(input, patchedPath);
			ApplyAction.run(patchedPath, List.of(signaturesPath.get()));

			return StepResult.SUCCESS;
		} else {
			return StepResult.NOT_RUN;
		}
	}

	public static final String ORNITHE_SPARROW_META = "https://meta.ornithemc.net/v3/versions/sparrow";

	private Map<String, OrnitheSparrowVersionMeta> sparrowVersions = null;

	private Optional<Path> getSignaturesPath(OrderedVersion mcVersion, MappingFlavour mappingFlavour) throws Exception {
		if (mcVersion.compareTo(GitCraftConfig.FIRST_MERGEABLE_VERSION) >= 0) {
			OrnitheSparrowVersionMeta sparrowBuild = getLatestSparrowBuild(mcVersion, Side.MERGED);
			return Optional.ofNullable(prepareSignatures(sparrowBuild, mcVersion, Side.MERGED, mappingFlavour));
		} else {
			OrnitheSparrowVersionMeta clientSparrowBuild = getLatestSparrowBuild(mcVersion, Side.CLIENT);
			OrnitheSparrowVersionMeta serverSparrowBuild = getLatestSparrowBuild(mcVersion, Side.SERVER);
			if (clientSparrowBuild == null && serverSparrowBuild == null) {
				return Optional.empty();
			}
			Path clientSignatures = prepareSignatures(clientSparrowBuild, mcVersion, Side.CLIENT, mappingFlavour);
			Path serverSignatures = prepareSignatures(serverSparrowBuild, mcVersion, Side.SERVER, mappingFlavour);
			if (clientSignatures == null) {
				return Optional.of(serverSignatures);
			}
			if (serverSignatures == null) {
				return Optional.of(clientSignatures);
			}
			Path mergedSignatures = getSparrowPath(clientSparrowBuild, serverSparrowBuild, mappingFlavour);
			if (!Files.exists(mergedSignatures)) {
				MappingUtils.mergeSignatures(clientSignatures, serverSignatures, mergedSignatures);
			}
			return Optional.of(mergedSignatures);
		}
	}

	public OrnitheSparrowVersionMeta getLatestSparrowBuild(OrderedVersion mcVersion, Side side) {
		if (sparrowVersions == null) {
			try {
				List<OrnitheSparrowVersionMeta> sparrowVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_SPARROW_META)), SerializationHelper.TYPE_LIST_ORNITHE_SPARROW_VERSION_META);
				sparrowVersions = sparrowVersionMetas.stream().collect(Collectors.groupingBy(OrnitheSparrowVersionMeta::gameVersion)).values().stream().map(OrnitheSparrowVersionMetas -> OrnitheSparrowVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheSparrowVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return sparrowVersions.get(mcVersion.launcherFriendlyVersionName() + side.suffix());
	}

	private Path prepareSignatures(OrnitheSparrowVersionMeta sparrowMeta, OrderedVersion mcVersion, Side side, MappingFlavour mappingFlavour) {
		if (sparrowMeta == null) {
			return null;
		}
		Path sparrowFile = getSparrowPath(sparrowMeta);
		if (Files.exists(sparrowFile)) {
			return sparrowFile;
		}
		Path sparrowJar = getSparrowJar(sparrowMeta);
		try {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(sparrowMeta.makeMavenURL(), new RemoteHelper.LocalFileInfo(sparrowJar, null, "signatures", sparrowMeta.version()));
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(sparrowJar)) {
				Path signaturesPathInJar = fs.get().getPath("signatures", "mappings.sigs");
				Files.copy(signaturesPathInJar, sparrowFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
		Path mappedSparrowFile = getSparrowPath(sparrowMeta, mappingFlavour);
		if (Files.exists(mappedSparrowFile)) {
			return mappedSparrowFile;
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
			MappingUtils.mapSignatures(sparrowFile, mappedSparrowFile, Format.TINY_V2, mappingsPath);

			Files.delete(mappingsPath);
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
		return mappedSparrowFile;
	}

	private Path getSparrowJar(OrnitheSparrowVersionMeta meta) {
		return GitCraftPaths.SPARROW.resolve("%s-sparrow+build.%s.jar".formatted(meta.gameVersion(), meta.build()));
	}

	private Path getSparrowPath(OrnitheSparrowVersionMeta meta) {
		return GitCraftPaths.SPARROW.resolve("%s-sparrow+build.%d.sigs".formatted(meta.gameVersion(), meta.build()));
	}

	private Path getSparrowPath(OrnitheSparrowVersionMeta meta, MappingFlavour mappingFlavour) {
		return GitCraftPaths.SPARROW.resolve("%s-sparrow+build.%d-%s.sigs".formatted(meta.gameVersion(), meta.build(), mappingFlavour.toString()));
	}

	private Path getSparrowPath(OrnitheSparrowVersionMeta clientMeta, OrnitheSparrowVersionMeta serverMeta, MappingFlavour mappingFlavour) {
		return GitCraftPaths.SPARROW.resolve("%s-sparrow+build.(%d-%d)-%s.sigs".formatted(clientMeta.gameVersion(), clientMeta.build(), serverMeta.build(), mappingFlavour.toString()));
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
