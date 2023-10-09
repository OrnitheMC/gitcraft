package com.github.winplay02;

import com.github.winplay02.meta.OrnitheFeatherVersionMeta;
import dex.mcgitmaker.GitCraft;
import dex.mcgitmaker.data.McVersion;
import dex.mcgitmaker.loom.FileSystemUtil;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingNsCompleter;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MappingHelper {

	public enum MappingFlavour {
		/*MOJMAP, FABRIC_INTERMEDIARY, YARN, MOJMAP_PARCHMENT, */CALAMUS, FEATHER;

		@Override
		public String toString() {
			return super.toString().toLowerCase(Locale.ROOT);
		}

		public boolean supportsComments() {
			return switch (this) {
				/*case MOJMAP_PARCHMENT -> true;*/
				case /*MOJMAP, YARN, FABRIC_INTERMEDIARY, */CALAMUS, FEATHER -> false;
			};
		}

		public String getSourceNS() {
			return MappingsNamespace.OFFICIAL.toString();
		}

		public String getDestinationNS() {
			return switch (this) {
				case /*MOJMAP, YARN, MOJMAP_PARCHMENT, */FEATHER -> MappingsNamespace.NAMED.toString();
				case /*FABRIC_INTERMEDIARY, */CALAMUS -> MappingsNamespace.INTERMEDIARY.toString();
			};
		}

		public boolean doMappingsExist(McVersion mcVersion) {
			return switch (this) {
				/*case MOJMAP_PARCHMENT -> {
					try {
						yield mcVersion.hasMappings && !mcVersion.snapshot && !GitCraftConfig.parchmentMissingVersions.contains(mcVersion.version) && SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.PARCHMENT_START_VERSION) >= 0;
					} catch (VersionParsingException e) {
						throw new RuntimeException(e);
					}
				}
				case MOJMAP -> mcVersion.hasMappings;
				case YARN -> {
					if (isYarnBrokenVersion(mcVersion)) { // exclude broken versions
						yield false;
					}
					try {
						yield SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.YARN_MAPPINGS_START_VERSION) >= 0;
					} catch (VersionParsingException e) {
						throw new RuntimeException(e);
					}
				}
				case FABRIC_INTERMEDIARY -> {
					try {
						yield SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.INTERMEDIARY_MAPPINGS_START_VERSION) >= 0;
					} catch (VersionParsingException e) {
						throw new RuntimeException(e);
					}
				}*/
				case CALAMUS -> {
					try {
						yield SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.CALAMUS_MAPPINGS_START_VERSION) >= 0 && SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.CALAMUS_MAPPINGS_END_VERSION) <= 0;
					} catch (VersionParsingException e) {
						throw new RuntimeException(e);
					}
				}
				case FEATHER -> {
					yield getFeatherLatestBuild(mcVersion) != null;
				}
			};
		}

		public Optional<Path> getMappingsPath(McVersion mcVersion) {
			return switch (this) {
				/*case MOJMAP_PARCHMENT -> {
					try {
						yield Optional.of(mappingsPathParchment(mcVersion));
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				case MOJMAP -> {
					try {
						yield Optional.of(mappingsPathMojMap(mcVersion));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				case YARN ->
					Optional.ofNullable(isYarnBrokenVersion(mcVersion) ? null : mappingsPathYarn(mcVersion)); // exclude broken versions
				case FABRIC_INTERMEDIARY -> Optional.ofNullable(mappingsPathIntermediary(mcVersion));*/
				case CALAMUS -> {
					yield Optional.of(mappingsPathCalamus(mcVersion));
				}
				case FEATHER -> {
					yield Optional.of(mappingsPathFeather(mcVersion));
				}
			};
		}

		public Optional<Path> getSimplifiedMappingsPath(McVersion mcVersion) {
			return switch (this) {
				case CALAMUS -> {
					yield Optional.of(mappingsPathCalamus(mcVersion));
				}
				case FEATHER -> {
					yield Optional.of(mappingsPathFeatherSimplified(mcVersion));
				}
			};
		}

		public IMappingProvider getMappingsProvider(McVersion mcVersion) {
			if (!doMappingsExist(mcVersion)) {
				MiscHelper.panic("Tried to use %s-mappings for version %s. These mappings do not exist for this version.", this, mcVersion.version);
			}
			Optional<Path> mappingsPath = getMappingsPath(mcVersion);
			if (mappingsPath.isEmpty()) {
				MiscHelper.panic("An error occurred while getting mapping information for %s (version %s)", this, mcVersion.version);
			}
			return TinyUtils.createTinyMappingProvider(mappingsPath.get(), getSourceNS(), getDestinationNS());
		}
	}

	public static final String ORNITHE_FEATHER_META = "https://meta.ornithemc.net/v3/versions/feather";

	private static Map<String, OrnitheFeatherVersionMeta> featherVersions = null;

	public static OrnitheFeatherVersionMeta getFeatherLatestBuild(McVersion mcVersion) {
		if (featherVersions == null) {
			try {
				List<OrnitheFeatherVersionMeta> featherVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_FEATHER_META)), SerializationHelper.TYPE_LIST_ORNITHE_FEATHER_VERSION_META);
				featherVersions = featherVersionMetas.stream().collect(Collectors.groupingBy(OrnitheFeatherVersionMeta::gameVersion)).values().stream().map(ornitheFeatherVersionMetas -> ornitheFeatherVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheFeatherVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return featherVersions.get(mcVersion.version);
	}

	public static MemoryMappingTree createIntermediaryMappingsProvider(McVersion mcVersion) throws IOException {
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		Path intermediaryPath = mappingsPathCalamus(mcVersion);
		if (intermediaryPath != null) {
			MappingReader.read(intermediaryPath, mappingTree);
		}
		return mappingTree;
	}

	private static Path mappingsPathFeather(McVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getFeatherLatestBuild(mcVersion);
		if (featherVersion == null) {
			// MiscHelper.panic("Tried to use feather for version %s. Feather mappings do not exist for this version.", mcVersion.version);
			MiscHelper.println("Tried to use feather for version %s. Feather mappings do not exist for this version in meta.ornithemc.net. Falling back to generated version...", mcVersion.version);
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.version, "+build.", 1, String.format("net.ornithemc:feather:%s+build.%s:unknown-fallback", mcVersion.version, 1), String.format("%s+build.%s", mcVersion.version, 1), !mcVersion.snapshot);
		}
		Path mappingsFile = GitCraft.MAPPINGS.resolve(String.format("%s-feather-build.%s.tiny", mcVersion.version, featherVersion.build()));
		if (mappingsFile.toFile().exists()) {
			return mappingsFile;
		}
		Path mappingsFileJar = GitCraft.MAPPINGS.resolve(String.format("%s-feather-build.%s.jar", mcVersion.version, featherVersion.build()));
		try {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetry(featherVersion.makeMavenURLMergedV2(), mappingsFileJar, null, "feather mapping", mcVersion.version);
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileJar)) {
				Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
				Files.copy(mappingsPathInJar, mappingsFile, StandardCopyOption.REPLACE_EXISTING);
			}
			return mappingsFile;
		} catch (IOException | RuntimeException ignored) {
			mappingsFileJar.toFile().delete();
		}
		MiscHelper.println("Merged Feather mappings do not exist for %s, merging with intermediary ourselves...", mcVersion.version);
		Path mappingsFileUnmerged = mappingsPathFeatherUnmerged(mcVersion, featherVersion);
		Path mappingsFileIntermediary = mappingsPathCalamus(mcVersion);
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		try {
			// Intermediary first
			MappingSourceNsSwitch nsSwitchIntermediary = new MappingSourceNsSwitch(mappingTree, MappingsNamespace.INTERMEDIARY.toString());
			MappingReader.read(mappingsFileIntermediary, nsSwitchIntermediary);
			// Then named yarn
			MappingSourceNsSwitch nsSwitchYarn = new MappingSourceNsSwitch(mappingTree, MappingsNamespace.INTERMEDIARY.toString());
			MappingReader.read(mappingsFileUnmerged, nsSwitchYarn);
			yarn_fixInnerClasses(mappingTree);
			try (MappingWriter writer = MappingWriter.create(mappingsFile, MappingFormat.TINY_2)) {
				MappingNsCompleter nsCompleter = new MappingNsCompleter(writer, Map.of(MappingsNamespace.NAMED.toString(), MappingsNamespace.INTERMEDIARY.toString()), true);
				MappingDstNsReorder dstReorder = new MappingDstNsReorder(nsCompleter, List.of(MappingsNamespace.INTERMEDIARY.toString(), MappingsNamespace.NAMED.toString()));
				MappingSourceNsSwitch sourceNsSwitch = new MappingSourceNsSwitch(dstReorder, MappingsNamespace.OFFICIAL.toString());
				mappingTree.accept(sourceNsSwitch);
			}
			return mappingsFile;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Path mappingsPathFeatherSimplified(McVersion mcVersion) {
		OrnitheFeatherVersionMeta featherVersion = getFeatherLatestBuild(mcVersion);
		if (featherVersion == null) {
			// MiscHelper.panic("Tried to use feather for version %s. Feather mappings do not exist for this version.", mcVersion.version);
			MiscHelper.println("Tried to use feather for version %s. Feather mappings do not exist for this version in meta.ornithemc.net. Falling back to generated version...", mcVersion.version);
			featherVersion = new OrnitheFeatherVersionMeta(mcVersion.version, "+build.", 1, String.format("net.ornithemc:feather:%s+build.%s:unknown-fallback", mcVersion.version, 1), String.format("%s+build.%s", mcVersion.version, 1), !mcVersion.snapshot);
		}
		Path mappingsFile = mappingsPathFeather(mcVersion);
		Path mappingsSimplifiedFile = GitCraft.MAPPINGS.resolve(String.format("%s-feather-build.%s-simplified.tiny", mcVersion.version, featherVersion.build()));
		if (mappingsSimplifiedFile.toFile().exists()) {
			return mappingsSimplifiedFile;
		}
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		try {
			MappingReader.read(mappingsFile, mappingTree);
			try (MappingWriter writer = MappingWriter.create(mappingsSimplifiedFile, MappingFormat.TINY_2)) {
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

	private static Path mappingsPathFeatherUnmerged(McVersion mcVersion, OrnitheFeatherVersionMeta featherVersion) {
		try {
			Path mappingsFileUnmerged = GitCraft.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s.tiny", mcVersion.version, featherVersion.build()));
			if (!mappingsFileUnmerged.toFile().exists()) {
				Path mappingsFileUnmergedJar = GitCraft.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s.jar", mcVersion.version, featherVersion.build()));
				RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetry(featherVersion.makeMavenURLUnmergedV2(), mappingsFileUnmergedJar, null, "unmerged feather mapping", mcVersion.version);
				try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileUnmergedJar)) {
					Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
					Files.copy(mappingsPathInJar, mappingsFileUnmerged, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			return mappingsFileUnmerged;
		} catch (IOException | RuntimeException e) {
			MiscHelper.println("Feather mappings in tiny-v2 format do not exist for %s, falling back to tiny-v1 mappings...", mcVersion.version);
			try {
				Path mappingsFileUnmergedv1 = GitCraft.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s-v1.tiny", mcVersion.version, featherVersion.build()));
				if (!mappingsFileUnmergedv1.toFile().exists()) {
					Path mappingsFileUnmergedJarv1 = GitCraft.MAPPINGS.resolve(String.format("%s-feather-unmerged-build.%s-v1.jar", mcVersion.version, featherVersion.build()));
					RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetry(featherVersion.makeMavenURLUnmergedV1(), mappingsFileUnmergedJarv1, null, "unmerged feather mapping (v1 fallback)", mcVersion.version);
					try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mappingsFileUnmergedJarv1)) {
						Path mappingsPathInJar = fs.get().getPath("mappings", "mappings.tiny");
						Files.copy(mappingsPathInJar, mappingsFileUnmergedv1, StandardCopyOption.REPLACE_EXISTING);
					}
				}
				return mappingsFileUnmergedv1;
			} catch (IOException e2) {
				MiscHelper.println("Feather mappings for version %s cannot be fetched. Giving up after trying merged-v2, v2, and v1 mappings.", mcVersion.version);
				throw new RuntimeException(e);
			}
		}
	}

	private static Path mappingsPathCalamus(McVersion mcVersion) {
		try {
			if (SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.CALAMUS_MAPPINGS_START_VERSION) < 0) {
				return null;
			}
			if (SemanticVersion.parse(mcVersion.loaderVersion).compareTo((Version) GitCraftConfig.CALAMUS_MAPPINGS_END_VERSION) > 0) {
				return null;
			}
		} catch (VersionParsingException e) {
			throw new RuntimeException(e);
		}
		Path mappingsFile = GitCraft.MAPPINGS.resolve(mcVersion.version + "-intermediary.tiny");
		if (!mappingsFile.toFile().exists()) {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetry(RemoteHelper.urlencodedURL(String.format("https://raw.githubusercontent.com/OrnitheMC/calamus/main/mappings/%s.tiny", mcVersion.version)), mappingsFile, null, "intermediary mapping", mcVersion.version);
		}
		return mappingsFile;
	}
}
