package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.stitch.merge.JarMerger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public abstract class MergeStep extends Step {

	protected final Path rootPath;

	protected MergeStep(Path rootPath) {
		this.rootPath = rootPath;
	}

	protected abstract boolean shouldRun(OrderedVersion mcVersion);

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour, MinecraftVersionGraph versionGraph, RepoWrapper repo) throws IOException {
		if (!shouldRun(mcVersion)) {
			return StepResult.NOT_RUN;
		}
		Path mergedPath = getInternalArtifactPath(mcVersion, mappingFlavour);
		if (Files.exists(mergedPath)) {
			return StepResult.UP_TO_DATE;
		}

		Path artifactRootPath = pipelineCache.getForKey(Step.STEP_FETCH_ARTIFACTS);
		if (artifactRootPath == null) {
			MiscHelper.panic("Artifacts (client jar, server jar) for version %s do not exist", mcVersion.launcherFriendlyVersionName());
		}

		if (mcVersion.hasClientCode() && mcVersion.hasServerCode()) {
			Path client = mcVersion.clientJar().resolve(artifactRootPath);
			Path server = mcVersion.serverJar().resolve(artifactRootPath);
			BundleMetadata sbm = BundleMetadata.fromJar(server);
			if (sbm != null) {
				Path minecraftExtractedServerJar = GitCraftPaths.MC_VERSION_STORE.resolve(mcVersion.launcherFriendlyVersionName()).resolve("extracted-server.jar");

				if (sbm.versions().size() != 1) {
					throw new UnsupportedOperationException("Expected only 1 version in META-INF/versions.list, but got %d".formatted(sbm.versions().size()));
				}

				unpackJarEntry(sbm.versions().get(0), server, minecraftExtractedServerJar);
				server = minecraftExtractedServerJar;
			}

			try (JarMerger jarMerger = new JarMerger(client.toFile(), server.toFile(), mergedPath.toFile())) {
				jarMerger.enableSyntheticParamsOffset();
				jarMerger.merge();
			}
		} else {
			// a bit of a hack, but it makes it so there's a file path you can rely on existing
			// for steps after remapping (nesting, unpick, decompiling, etc.)
			if (mcVersion.hasClientCode()) {
				Files.copy(mcVersion.clientJar().resolve(artifactRootPath), mergedPath);
			}
			if (mcVersion.hasServerCode()) {
				Files.copy(mcVersion.serverJar().resolve(artifactRootPath), mergedPath);
			}
		}
		
		return StepResult.SUCCESS;
	}

	private void unpackJarEntry(BundleMetadata.Entry entry, Path jar, Path dest) throws IOException {
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar); InputStream is = Files.newInputStream(fs.get().getPath(entry.path()))) {
			Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
