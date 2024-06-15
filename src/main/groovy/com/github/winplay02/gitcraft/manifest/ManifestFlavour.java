package com.github.winplay02.gitcraft.manifest;

import java.util.Locale;
import java.util.concurrent.Callable;

public enum ManifestFlavour {
	MINECRAFT_LAUNCHER(MinecraftLauncherManifest::new),
	SKYRISING(SkyrisingManifest::new);

	private final Callable<ManifestProvider> manifestProviderSupplier;
	private ManifestProvider manifestProvider;

	ManifestFlavour(Callable<ManifestProvider> manifestProviderSupplier) {
		this.manifestProviderSupplier = manifestProviderSupplier;
	}

	public void init() throws Exception {
		if (this.manifestProvider == null) {
			this.manifestProvider = this.manifestProviderSupplier.call();
		}
	}

	public ManifestProvider getManifestProvider() {
		return this.manifestProvider;
	}

	@Override
	public String toString() {
		return super.toString().toLowerCase(Locale.ROOT);
	}
}
