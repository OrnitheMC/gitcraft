package com.github.winplay02.gitcraft.manifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

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
		return getVersionDetails(mcVersion.launcherFriendlyVersionName()).previous();
	}
}
