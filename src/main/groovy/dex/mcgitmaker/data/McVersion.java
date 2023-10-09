package dex.mcgitmaker.data;

import com.github.winplay02.MappingHelper;
import com.github.winplay02.MiscHelper;
import dex.mcgitmaker.GitCraft;
import dex.mcgitmaker.loom.BundleMetadata;
import dex.mcgitmaker.loom.Decompiler;
import net.fabricmc.stitch.merge.JarMerger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

public class McVersion {

	public McVersion(String version, String normalizedVersion, String loaderVersion, String previousVersion, boolean snapshot, boolean hasMappings, int javaVersion, McArtifacts artifacts, Collection<Artifact> libraries, String mainClass, String mergedJar, String time, String assets_index) {
		this.version = version;
		this.normalizedVersion = normalizedVersion;
		this.loaderVersion = loaderVersion;
		this.previousVersion = previousVersion;
		this.snapshot = snapshot;
		this.hasMappings = hasMappings;
		this.javaVersion = javaVersion;
		this.artifacts = artifacts;
		this.libraries = libraries;
		this.mainClass = mainClass;
		this.mergedJar = mergedJar;
		this.time = time;
		this.assets_index = assets_index;
	}

	public String version; // MC version string from launcher
	public String normalizedVersion; // normalized MC version string from details json
	public String loaderVersion; // Version from Fabric loader
	public String previousVersion; // previous MC version in the graph
	public boolean snapshot; // If the version is a release
	public boolean hasMappings; // If the version has mappings provided
	public int javaVersion = 8;
	public McArtifacts artifacts;
	public Collection<Artifact> libraries; // The libraries for this version
	public String mainClass;
	public String mergedJar; // merged client and server

	public String time;
	public String assets_index;

	public Path decompiledMc(MappingHelper.MappingFlavour mappingFlavour) throws IOException {
		Path p = Decompiler.decompiledPath(this, mappingFlavour);
		File f = p.toFile();
		if (!f.exists() || f.length() == 22 /* empty jar */) {
			Decompiler.decompile(this, mappingFlavour);
		}
		return p;
	}

	public boolean removeDecompiled(MappingHelper.MappingFlavour mappingFlavour) throws IOException {
		Path p = Decompiler.decompiledPath(this, mappingFlavour);
		File f = p.toFile();
		if (f.exists()) {
			Files.delete(p);
			return true;
		}
		return false;
	}

	public Path mergedJarPath() {
		return mergedJar == null ? GitCraft.MC_VERSION_STORE.resolve(version).resolve("merged-jar.jar") : Paths.get(mergedJar);
	}

	public File merged() throws IOException {
		Path p = mergedJarPath();
		File f = p.toFile();
		if (!f.exists()) {
			makeMergedJar();
		}
		return f;
	}

	void makeMergedJar() throws IOException {
		MiscHelper.println("Merging jars... %s", version);
		File client = artifacts.clientJar().fetchArtifact();
		File server2merge = artifacts.serverJar().fetchArtifact();

		BundleMetadata sbm = BundleMetadata.fromJar(server2merge.toPath());
		if (sbm != null) {
			Path minecraftExtractedServerJar = GitCraft.MC_VERSION_STORE.resolve(version).resolve("extracted-server.jar");

			if (sbm.versions.size() != 1) {
				throw new UnsupportedOperationException("Expected only 1 version in META-INF/versions.list, but got %d".formatted(sbm.versions.size()));
			}

			sbm.versions.get(0).unpackEntry(server2merge.toPath(), minecraftExtractedServerJar);
			server2merge = minecraftExtractedServerJar.toFile();
		}

		Path temp = mergedJarPath().resolveSibling("merged-unfiltered.jar");

		try (JarMerger jarMerger = new JarMerger(client, server2merge, temp.toFile())) {
			jarMerger.enableSyntheticParamsOffset();
			jarMerger.merge();
		}

		try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(mergedJarPath().toFile()))) {
			byte[] buffer = new byte[4096];
			int read = 0;

			try (JarInputStream jis = new JarInputStream(new FileInputStream(temp.toFile()))) {
				for (JarEntry entry; (entry = jis.getNextJarEntry()) != null; ) {
					String name = entry.getName();

					// always copy non-class files
					// copy classes if in root package, or in net/minecraft/ or in com/mojang/
					if (!name.endsWith(".class") || name.indexOf('/') < 0 || name.startsWith("net/minecraft/") || name.startsWith("com/mojang/")) {
						jos.putNextEntry(new JarEntry(name));

						while ((read = jis.read(buffer)) > 0) {
							jos.write(buffer, 0, read);
						}

						jos.flush();
						jos.closeEntry();
					}
				}
			}
		}

		mergedJar = mergedJarPath().toString();
	}

	public String toCommitMessage() {
		return this.version + "\n\nSemVer: " + this.loaderVersion;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		McVersion mcVersion = (McVersion) o;
		return snapshot == mcVersion.snapshot && hasMappings == mcVersion.hasMappings && javaVersion == mcVersion.javaVersion && Objects.equals(version, mcVersion.version) && Objects.equals(normalizedVersion, mcVersion.normalizedVersion) && Objects.equals(loaderVersion, mcVersion.loaderVersion) && Objects.equals(artifacts, mcVersion.artifacts) && Objects.equals(libraries, mcVersion.libraries) && Objects.equals(mainClass, mcVersion.mainClass) && Objects.equals(time, mcVersion.time) && Objects.equals(assets_index, mcVersion.assets_index);
	}

	@Override
	public int hashCode() {
		return Objects.hash(version, normalizedVersion, loaderVersion, previousVersion, snapshot, hasMappings, javaVersion, artifacts, libraries, mainClass, time, assets_index);
	}
}
