package com.github.winplay02.gitcraft.manifest;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.SerializationHelper;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.impl.game.minecraft.McVersion;
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public abstract class BaseMetadataProvider<M extends VersionsManifest<E>, E extends VersionsManifest.VersionEntry> implements MetadataProvider {
	protected final Path manifestMetadata;
	protected final Path remoteMetadata;
	protected final Path localMetadata;
	protected final List<MetadataSources.RemoteVersionsManifest<M, E>> manifestSources;
	protected final List<MetadataSources.RemoteMetadata<E>> metadataSources;
	protected final List<MetadataSources.LocalRepository> repositorySources;
	protected final LinkedHashMap<String, OrderedVersion> versionsById = new LinkedHashMap<>();
	protected final TreeMap<String, String> semverCache = new TreeMap<>();
	protected final Predicate<String> versionFilter = new VersionFilter();
	private boolean versionsLoaded;

	protected BaseMetadataProvider() {
		this.manifestMetadata = GitCraftPaths.MC_VERSION_META_STORE.resolve(this.getInternalName());
		this.remoteMetadata = GitCraftPaths.MC_VERSION_META_DOWNLOADS.resolve(this.getInternalName());
		this.localMetadata = GitCraftPaths.SOURCE_EXTRA_VERSIONS.resolve(this.getInternalName());
		this.manifestSources = new ArrayList<>();
		this.metadataSources = new ArrayList<>();
		this.repositorySources = new ArrayList<>();
	}

	protected void addManifestSource(String url, Class<M> manifestClass) {
		this.manifestSources.add(MetadataSources.RemoteVersionsManifest.of(url, manifestClass));
	}

	protected void addMetadataSource(E manifestEntry) {
		this.metadataSources.add(MetadataSources.RemoteMetadata.of(manifestEntry));
	}

	protected void addMetadataRepository(String directory) {
		this.repositorySources.add(MetadataSources.LocalRepository.of(this.localMetadata.resolve(directory)));
	}

	@Override
	public final void provide() throws IOException {
		this.loadVersions();
		this.postLoadVersions();
	}

	@Override
	public final Map<String, OrderedVersion> getVersions() {
		if (!this.versionsLoaded) {
			MiscHelper.panic("cannot access versions before metadata provider has been run!");
		}
		return Collections.unmodifiableMap(this.versionsById);
	}

	private void loadVersions() throws IOException {
		this.versionsById.clear();
		MiscHelper.println("Loading available versions from '%s'...", this.getName());
		for (MetadataSources.RemoteVersionsManifest<M, E> manifestSource : this.manifestSources) {
			MiscHelper.println("Reading versions manifest from %s...", manifestSource.url());
			M manifest = this.fetchVersionsManifest(manifestSource);
			for (E versionEntry : manifest.versions()) {
				if (!this.versionsById.containsKey(versionEntry.id())) {
					if (this.shouldLoadVersion(versionEntry.id())) {
						this.versionsById.put(versionEntry.id(), this.loadVersionFromManifest(versionEntry, this.manifestMetadata));
					}
				} else {
					if (this.isExistingVersionMetadataValid(versionEntry, this.manifestMetadata)) {
						MiscHelper.println("WARNING: Found duplicate manifest version entry: %s (Matches previous entry)", versionEntry.id());
					} else {
						MiscHelper.panic("Found duplicate manifest version enryt: %s (Differs from previous)", versionEntry.id());
					}
				}
			}
		}
		for (MetadataSources.RemoteMetadata<E> metadataSource : this.metadataSources) {
			MiscHelper.println("Reading extra metadata for %s...", metadataSource.versionEntry().id());
			E versionEntry = metadataSource.versionEntry();
			if (!this.versionsById.containsKey(versionEntry.id())) {
				if (this.shouldLoadVersion(versionEntry.id())) {
					this.versionsById.put(versionEntry.id(), this.loadVersionFromManifest(versionEntry, this.remoteMetadata));
				}
			} else {
				MiscHelper.panic("Found duplicate extra version entry: %s (Differs from previous)", versionEntry.id());
			}
		}
		for (MetadataSources.LocalRepository repository : this.repositorySources) {
			MiscHelper.println("Reading extra metadata repository from %s...", repository.directory());
			Path dir = repository.directory();
			this.loadVersionsFromRepository(dir, mcVersion -> {
				String versionId = mcVersion.launcherFriendlyVersionName();
				if (!this.versionsById.containsKey(versionId)) {
					if (this.shouldLoadVersion(versionId)) {
						this.versionsById.put(versionId, mcVersion);
					}
				} else {
					MiscHelper.panic("Found duplicate repository version entry: %s", versionId);
				}
			});
		}
		this.versionsLoaded = true;
	}

	/**
	 * @return whether this Minecraft version should be loaded or not
	 */
	protected boolean shouldLoadVersion(String versionId) {
		return this.versionFilter.test(versionId);
	}

	protected void postLoadVersions() {
	}

	private final M fetchVersionsManifest(MetadataSources.RemoteVersionsManifest<M, E> manifestSource) throws IOException {
		try {
			return SerializationHelper.deserialize(SerializationHelper.fetchAllFromURL(new URI(manifestSource.url()).toURL()), manifestSource.manifestClass());
		} catch (MalformedURLException | URISyntaxException e) {
			throw new IOException("unable to fetch versions manifest", e);
		}
	}

	/**
	 * Fetch all the metadata provided by this manifest entry and return an {@link OrderedVersion}
	 * representing this version.
	 *
	 * @param manifestEntry   Version Entry from Versions Manifest
	 * @return OrderedVersion Version
	 * @throws IOException on failure
	 */
	protected abstract OrderedVersion loadVersionFromManifest(E manifestEntry, Path targetDir) throws IOException;

	/**
	 * Fetch all the metadata provided by this repository and pass them to the given version loader.
	 */
	protected abstract void loadVersionsFromRepository(Path dir, Consumer<OrderedVersion> loader) throws IOException;

	protected final <T> T fetchVersionMetadata(String id, String url, String sha1, Path targetDir, String targetFileKind, Class<T> metadataClass) throws IOException {
		String fileName = url.substring(url.lastIndexOf('/') + 1);
		Path filePath = targetDir.resolve(fileName);
		RemoteHelper.downloadToFileWithChecksumIfNotExists(url, new RemoteHelper.LocalFileInfo(filePath, sha1, targetFileKind, id), RemoteHelper.SHA1);
		return this.loadVersionMetadata(filePath, metadataClass);
	}

	protected final <T> T loadVersionMetadata(Path targetFile, Class<T> metadataClass) throws IOException {
		String fileName = targetFile.getFileName().toString();
		if (!fileName.endsWith(".json")) {
			if (fileName.endsWith(".zip")) {
				fileName = fileName.substring(0, fileName.length() - ".zip".length()) + ".json";
				try (FileSystem fs = FileSystems.newFileSystem(targetFile)) {
					Optional<Path> zipFile = MiscHelper.findRecursivelyByName(fs.getPath("."), fileName);
					if (zipFile.isPresent()) {
						return this.loadVersionMetadata(zipFile.get(), metadataClass);
					} else {
						MiscHelper.panic("cannot find metadata file json inside %s", targetFile);
					}
				}
			} else {
				MiscHelper.panic("unknown file extension for metadata file %s", targetFile);
			}
		}
		T metadata = SerializationHelper.deserialize(SerializationHelper.fetchAllFromPath(targetFile), metadataClass);
		if (metadata == null) {
			MiscHelper.panic("unable to load metadata type %s from file %s", metadataClass.getName(), targetFile);
		}
		return metadata;
	}

	/**
	 * Check whether existing (cached) version metadata files are valid.
	 * Check whether an existing (cached) version meta file is valid. This file was read previously from another meta source or a single file.
	 *
	 * @param manifestEntry Version Entry from Versions Manifest
	 * @return True if the read file is compatible with the new source provided by the passed launcher meta version entry, otherwise false.
	 */
	protected abstract boolean isExistingVersionMetadataValid(E manifestEntry, Path targetDir) throws IOException;

	protected final boolean isExistingVersionMetadataValid(String id, String url, String sha1, Path targetDir) throws IOException {
		String fileName = url.substring(url.lastIndexOf('/') + 1);
		Path filePath = targetDir.resolve(fileName);
		return Files.exists(filePath) && (sha1 == null || RemoteHelper.SHA1.fileMatchesChecksum(filePath, sha1));
	}

	@Override
	public final OrderedVersion getVersionByVersionID(String versionId) {
		return this.getVersions().get(versionId);
	}

	@Override
	public boolean shouldExcludeFromMainBranch(OrderedVersion mcVersion) {
		return mcVersion.isPending();
	}

	private static class VersionFilter implements Predicate<String> {

		private String[] onlyVersions = null;
		private String[] excludedVersions = null;
		private McVersion minVersion = null;
		private McVersion maxVersion = null;

		VersionFilter() {
			if (GitCraft.config.isOnlyVersion()) {
				this.onlyVersions = GitCraft.config.onlyVersion;
			}
			if (GitCraft.config.isAnyVersionExcluded()) {
				this.excludedVersions = GitCraft.config.excludedVersion;
			}
			if (GitCraft.config.isMinVersion()) {
				this.minVersion = this.findVersion(GitCraft.config.minVersion, "minimum");
			}
			if (GitCraft.config.isMaxVersion()) {
				this.maxVersion = this.findVersion(GitCraft.config.maxVersion, "maximum");
			}
		}

		@Override
		public boolean test(String versionId) {
			if (this.onlyVersions != null) {
				for (String onlyVersion : this.onlyVersions) {
					if (versionId.equals(onlyVersion)) {
						return true;
					}
				}
			}
			if (this.excludedVersions != null) {
				for (String excludedVersion : this.excludedVersions) {
					if (versionId.equals(excludedVersion)) {
						return false;
					}
				}
			}
			if (this.minVersion != null || this.maxVersion != null) {
				McVersion version = this.findVersion(versionId, "provided");
				if (version != null) {
					if (this.minVersion != null) {
						if (this.compareVersions(version, this.minVersion) < 0) {
							return false;
						}
					}
					if (this.maxVersion != null) {
						if (this.compareVersions(version, this.maxVersion) > 0) {
							return false;
						}
					}
				}
			}
			return true;
		}

		private McVersion findVersion(String versionId, String kind) {
			try {
				return McVersionLookup.getVersion(Collections.emptyList(), null, versionId);
			} catch (Throwable t) {
				MiscHelper.println("unable to parse %s Minecraft version %s - providing metadata may take longer!", kind, versionId);
			}

			return null;
		}

		private int compareVersions(McVersion version1, McVersion version2) {
			SemanticVersion semanticVersion1 = null;
			SemanticVersion semanticVersion2 = null;

			try {
				semanticVersion1 = SemanticVersion.parse(version1.getNormalized());
				semanticVersion2 = SemanticVersion.parse(version2.getNormalized());
	
				return OrderedVersion.compare(semanticVersion1, semanticVersion2);
			} catch (Throwable t) {
				McVersion failedVersion = (semanticVersion1 == null) ? version1 : version2;
				MiscHelper.println("unable to parse %s as semantic version", failedVersion.getNormalized());
			}

			return 0;
		}
	}
}
