package net.ornithemc;

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

import com.github.winplay02.MappingHelper;
import com.github.winplay02.RemoteHelper;
import com.github.winplay02.SerializationHelper;
import com.github.winplay02.meta.OrnitheNestsVersionMeta;

import dex.mcgitmaker.GitCraft;
import dex.mcgitmaker.data.McVersion;
import dex.mcgitmaker.loom.FileSystemUtil;
import net.ornithemc.mappingutils.MappingUtils;
import net.ornithemc.mappingutils.io.Format;

public class NestHelper {

	public static Optional<Path> getNestsPath(McVersion mcVersion) {
		return Optional.ofNullable(nestsPath(mcVersion));
	}

	public static Optional<Path> getNestsPath(McVersion mcVersion, MappingHelper.MappingFlavour mappingFlavour) {
		return Optional.ofNullable(nestsPath(mcVersion, mappingFlavour));
	}

	public static final String ORNITHE_NESTS_META = "https://meta.ornithemc.net/v3/versions/nests";

	private static Map<String, OrnitheNestsVersionMeta> nestsVersions = null;

	public static OrnitheNestsVersionMeta getNestsLatestBuild(McVersion mcVersion) {
		if (nestsVersions == null) {
			try {
				List<OrnitheNestsVersionMeta> nestsVersionMetas = SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URL(ORNITHE_NESTS_META)), SerializationHelper.TYPE_LIST_ORNITHE_NESTS_VERSION_META);
				nestsVersions = nestsVersionMetas.stream().collect(Collectors.groupingBy(OrnitheNestsVersionMeta::gameVersion)).values().stream().map(ornitheNestsVersionMetas -> ornitheNestsVersionMetas.stream().max(Comparator.naturalOrder())).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toMap(OrnitheNestsVersionMeta::gameVersion, Function.identity()));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return nestsVersions.get(mcVersion.version);
	}

	private static Path nestsPath(McVersion mcVersion) {
		OrnitheNestsVersionMeta nestsVersion = getNestsLatestBuild(mcVersion);
		if (nestsVersion == null) {
			return null;
		}
		Path nestsFile = GitCraft.NESTS.resolve(String.format("%s-nests-build.%s.nest", mcVersion.version, nestsVersion.build()));
		if (nestsFile.toFile().exists()) {
			return nestsFile;
		}
		Path nestsFileJar = GitCraft.NESTS.resolve(String.format("%s-nests-build.%s.jar", mcVersion.version, nestsVersion.build()));
		try {
			RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetry(nestsVersion.makeMavenURL(), nestsFileJar, null, "nests", mcVersion.version);
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(nestsFileJar)) {
				Path nestsPathInJar = fs.get().getPath("nests", "mappings.nest");
				Files.copy(nestsPathInJar, nestsFile, StandardCopyOption.REPLACE_EXISTING);
			}
			return nestsFile;
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
	}

	private static Path nestsPath(McVersion mcVersion, MappingHelper.MappingFlavour mappingFlavour) {
		OrnitheNestsVersionMeta nestsVersion = getNestsLatestBuild(mcVersion);
		if (nestsVersion == null) {
			return null;
		}
		Path nestsFile = nestsPath(mcVersion);
		Path mappedNestsFile = GitCraft.NESTS.resolve(String.format("%s-nests-build.%s-%s.nest", mcVersion.version, nestsVersion.build(), mappingFlavour.toString()));
		if (mappedNestsFile.toFile().exists()) {
			return mappedNestsFile;
		}
		try {
			MappingUtils.mapNests(nestsFile, mappedNestsFile, Format.TINY_V2, mappingFlavour.getSimplifiedMappingsPath(mcVersion).get());
			return mappedNestsFile;
		} catch (IOException | RuntimeException e) {
			throw new RuntimeException(e);
		}
	}
}
