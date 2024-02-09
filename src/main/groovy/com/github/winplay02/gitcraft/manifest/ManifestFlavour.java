package com.github.winplay02.gitcraft.manifest;

import com.github.winplay02.gitcraft.GitCraft;

import java.util.Locale;

public enum ManifestFlavour {
	MINECRAFT_LAUNCHER(GitCraft.MINECRAFT_LAUNCHER),
	SKYRISING(GitCraft.SKYRISING);

	private final ManifestProvider manifestProvider;

	ManifestFlavour(ManifestProvider manifestProvider) {
		this.manifestProvider = manifestProvider;
	}

	public ManifestProvider getManifestProvider() {
		return this.manifestProvider;
	}

	@Override
	public String toString() {
		return super.toString().toLowerCase(Locale.ROOT);
	}
}
