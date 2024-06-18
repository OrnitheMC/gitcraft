package com.github.winplay02.gitcraft.manifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import com.github.winplay02.gitcraft.meta.LauncherMeta;
import com.github.winplay02.gitcraft.meta.VersionDetails;
import com.github.winplay02.gitcraft.meta.VersionMeta;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import com.github.winplay02.gitcraft.util.SerializationHelper;

public class SkyrisingManifest extends ManifestProvider {
	protected LinkedHashMap<String, VersionDetails> versionDetails = new LinkedHashMap<>();

	public SkyrisingManifest() {
		super(new DescribedURL[]{new DescribedURL("https://skyrising.github.io/mc-versions/version_manifest.json", "Launcher Meta")});
	}

	@Override
	public String getName() {
		return "Skyrising Minecraft Launcher Main Meta";
	}

	@Override
	public String getInternalName() {
		return "skyrising-launcher";
	}

	@Override
	protected void unpackLauncherMetaEntry(LauncherMeta.LauncherVersionEntry version) throws IOException {
		// the server versions in classic and alpha can not be merged
		// with the client versions at all, and thus have separate entries
		// in the manifest
		// it's somewhat arbitrary but we'll ignore those server versions
		// in favor creating a full graph for the client for these ancient
		// versions
		if (version.id().startsWith("server-")) {
			return;
		}

		Path versionDetailsPath = this.rootPath.resolve(version.id() + "-details.json");

		if (!versionDetails.containsKey(version.id())) {
			versionDetails.put(version.id(), loadVersionDetails(versionDetailsPath, version.id(), version.details()));
		} else {
			if (RemoteHelper.SHA1.fileMatchesChecksum(versionDetailsPath, version.sha1())) {
				MiscHelper.println("WARNING: Found duplicate version meta for version: %s (Matches previous entry)", version.id());
			} else {
				MiscHelper.panic("Found duplicate version meta for version: %s (Differs from previous)", version.id());
			}
		}

		super.unpackLauncherMetaEntry(version);
	}

	private VersionDetails loadVersionDetails(Path versionDetails, String versionId, String versionUrl) throws IOException {
		RemoteHelper.downloadToFileWithChecksumIfNotExists(versionUrl, new RemoteHelper.LocalFileInfo(versionDetails, null, "version details", versionId), RemoteHelper.SHA1);
		return SerializationHelper.deserialize(SerializationHelper.fetchAllFromPath(versionDetails), VersionDetails.class);
	}

	private VersionDetails getVersionDetails(String mcVersion) {
		return versionDetails.computeIfAbsent(mcVersion, key -> {
			throw new RuntimeException("no version details for " + key);
		});
	}

	@Override
	protected String lookupSemanticVersion(VersionMeta versionMeta) {
		return getVersionDetails(versionMeta.id()).normalizedVersion();
	}

	@Override
	public List<String> getParentVersion(OrderedVersion mcVersion) {
		List<String> parentVersions = new ArrayList<>();

		if (!patchParentVersions(mcVersion, parentVersions)) {
			VersionDetails details = getVersionDetails(mcVersion.launcherFriendlyVersionName());

			for (String parentVersion : details.previous()) {
				// Skyrising's manifest does not have a version with id rd-132211
				// yet version rd-132211-launcher lists it as its previous version
				if ("rd-132211".equals(parentVersion)) {
					continue;
				}
				// see comment in unpackLauncherMetaEntry for why ancient server is ignored
				if (parentVersion.startsWith("server-")) {
					continue;
				}

				VersionDetails parentDetails = getVersionDetails(parentVersion);
				parentVersions.add(parentDetails.normalizedVersion());
			}
		}

		return parentVersions;
	}

	// hopefully this will be replaced by more sophisticated branching logic
	// in the version graph and commit step
	private boolean patchParentVersions(OrderedVersion mcVersion, List<String> parentVersions) {
		String patchedParentVersion = switch (mcVersion.launcherFriendlyVersionName()) {
			case "12w32a"          -> "1.3.2";  // 1.3.1
			case "12w34a"          -> "12w32a"; // [1.3.2, 12w32a]
			case "13w16-04192037"  -> "1.5.2";  // 1.5.1
			case "13w36a-09051446" -> "1.6.4";  // 1.6.2-091847
			case "14w02a"          -> "1.7.10"; // 1.7.4
			case "15w31a"          -> "1.8.9";  // 1.8.8
			default -> null;
		};

		if (patchedParentVersion == null) {
			return false;
		}

		VersionDetails parentDetails = getVersionDetails(patchedParentVersion);
		parentVersions.add(parentDetails.normalizedVersion());

		return true;
	}
}
