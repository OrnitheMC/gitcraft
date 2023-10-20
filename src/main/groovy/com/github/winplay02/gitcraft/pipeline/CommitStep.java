package com.github.winplay02.gitcraft.pipeline;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.MinecraftVersionGraph;
import com.github.winplay02.gitcraft.mappings.MappingFlavour;
import com.github.winplay02.gitcraft.meta.AssetsIndexMeta;
import com.github.winplay02.gitcraft.types.AssetsIndex;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.MiscHelper;
import com.github.winplay02.gitcraft.util.RepoWrapper;
import net.fabricmc.loom.util.FileSystemUtil;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.StreamSupport;

public class CommitStep extends Step {

	private MinecraftVersionGraph versionGraph = null;
	private RepoWrapper repo = null;

	public void prepare(MinecraftVersionGraph versionGraph, RepoWrapper repo) {
		this.versionGraph = versionGraph;
		this.repo = repo;
	}

	@Override
	public String getName() {
		return STEP_COMMIT;
	}

	@Override
	public boolean preconditionsShouldRun(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour) {
		return !GitCraft.config.noRepo && super.preconditionsShouldRun(pipelineCache, mcVersion, mappingFlavour);
	}

	@Override
	public StepResult run(PipelineCache pipelineCache, OrderedVersion mcVersion, MappingFlavour mappingFlavour) throws Exception {
		// Check validity of prepared args
		Objects.requireNonNull(this.versionGraph);
		Objects.requireNonNull(this.repo);
		// Clean First
		MiscHelper.executeTimedStep("Clearing working directory...", this::clearWorkingTree);
		// Switch Branch
		Optional<String> target_branch = switchBranchIfNeeded(mcVersion);
		if (target_branch.isEmpty()) {
			return StepResult.UP_TO_DATE;
		}
		// Copy to repository
		MiscHelper.executeTimedStep("Moving files to repo...", () -> {
			// Copy decompiled MC code to repo directory
			copyCode(pipelineCache, mcVersion);
			// Copy assets & data (it makes sense to track them, atleast the data)
			copyAssets(pipelineCache, mcVersion);
			// External Assets
			copyExternalAssets(pipelineCache, mcVersion);
		});
		// Commit
		MiscHelper.executeTimedStep("Committing files to repo...", () -> createCommit(mcVersion));
		MiscHelper.println("Committed %s to the repository! (Target Branch is %s)", mcVersion.launcherFriendlyVersionName(), target_branch.orElseThrow() + (MinecraftVersionGraph.isVersionNonLinearSnapshot(mcVersion) ? " (non-linear)" : ""));
		return StepResult.SUCCESS;
	}

	private void clearWorkingTree() throws IOException {
		for (Path innerPath : MiscHelper.listDirectly(this.repo.getRootPath())) {
			if (!innerPath.toString().endsWith(".git")) {
				if (Files.isDirectory(innerPath)) {
					MiscHelper.deleteDirectory(innerPath);
				} else {
					Files.deleteIfExists(innerPath);
				}
			}
		}
	}

	private Optional<String> switchBranchIfNeeded(OrderedVersion mcVersion) throws IOException, GitAPIException {
		String target_branch;
		if (this.repo.getGit().getRepository().resolve(Constants.HEAD) != null) { // Don't run on empty repo
			target_branch = MinecraftVersionGraph.isVersionNonLinearSnapshot(mcVersion) ? mcVersion.launcherFriendlyVersionName() : GitCraft.config.gitMainlineLinearBranch;
			checkoutVersionBranch(target_branch.replace(" ", "-"), mcVersion);
			if (findVersionCurrentBranch(mcVersion)) {
				MiscHelper.println("Version %s already exists in repo, skipping", mcVersion.launcherFriendlyVersionName());
				return Optional.empty();
			}
			if (mcVersion.equals(this.versionGraph.getRootVersion())) {
				MiscHelper.panic("HEAD is not empty, but the current version is the root version, and should be the initial commit");
			}
			Optional<RevCommit> tip_commit = StreamSupport.stream(this.repo.getGit().log().setMaxCount(1).call().spliterator(), false).findFirst();
			if (tip_commit.isEmpty()) {
				MiscHelper.panic("HEAD is not empty, but a root commit can not be found");
			}
			Optional<OrderedVersion> prev_version = this.versionGraph.getPreviousNode(mcVersion);
			if (prev_version.isEmpty()) {
				MiscHelper.panic("HEAD is not empty, but current version does not have a preceding version");
			}
			if (!Objects.equals(tip_commit.get().getFullMessage(), prev_version.get().toCommitMessage())) {
				MiscHelper.panic("This repository is wrongly ordered. Please remove the unordered commits or delete the entire repository");
			}
		} else {
			if (!mcVersion.equals(this.versionGraph.getRootVersion())) {
				MiscHelper.panic("A non-root version is committed as the root commit to the repository");
			}
			target_branch = GitCraft.config.gitMainlineLinearBranch;
		}
		return Optional.of(target_branch);
	}

	private boolean findVersionCurrentBranch(OrderedVersion mcVersion) throws GitAPIException, IOException {
		return this.repo.getGit().log().all().setRevFilter(new CommitMsgFilter(mcVersion.toCommitMessage())).call().iterator().hasNext();
	}

	private void checkoutVersionBranch(String target_branch, OrderedVersion mcVersion) throws IOException, GitAPIException {
		if (!Objects.equals(this.repo.getGit().getRepository().getBranch(), target_branch)) {
			Ref target_ref = this.repo.getGit().getRepository().getRefDatabase().findRef(target_branch);
			if (target_ref == null) {
				RevCommit branchPoint = findBaseForNonLinearVersion(mcVersion);
				if (branchPoint == null) {
					MiscHelper.panic("Could not find branching point for non-linear version: %s (%s)", mcVersion.launcherFriendlyVersionName(), mcVersion.semanticVersion());
				}
				target_ref = this.repo.getGit().branchCreate().setStartPoint(branchPoint).setName(target_branch).call();
			}
			switchHEAD(target_ref);
		}
	}

	private RevCommit findBaseForNonLinearVersion(OrderedVersion mcVersion) throws IOException, GitAPIException {
		Optional<OrderedVersion> previousVersion = this.versionGraph.getPreviousNode(mcVersion);
		if (previousVersion.isEmpty()) {
			MiscHelper.panic("Cannot commit non-linear version %s, as the base version was not found", mcVersion.launcherFriendlyVersionName());
		}
		return findVersionObjectCurrentBranch(previousVersion.get());
	}

	private RevCommit findVersionObjectCurrentBranch(OrderedVersion mcVersion) throws GitAPIException, IOException {
		Iterator<RevCommit> iterator = this.repo.getGit().log().all().setRevFilter(new CommitMsgFilter(mcVersion.toCommitMessage())).call().iterator();
		if (iterator.hasNext()) {
			return iterator.next();
		}
		return null;
	}

	private void switchHEAD(Ref ref) throws IOException {
		RefUpdate refUpdate = this.repo.getGit().getRepository().getRefDatabase().newUpdate(Constants.HEAD, false);
		RefUpdate.Result result = refUpdate.link(ref.getName());
		if (result != RefUpdate.Result.FORCED) {
			MiscHelper.panic("Unsuccessfully changed HEAD to %s, result was: %s", ref, result);
		}
	}

	private void copyCode(PipelineCache pipelineCache, OrderedVersion mcVersion) throws IOException {
		Path decompiledJarPath = pipelineCache.getForKey(Step.STEP_DECOMPILE);
		if (decompiledJarPath == null) {
			MiscHelper.panic("A decompiled JAR for version %s does not exist", mcVersion.launcherFriendlyVersionName());
		}
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(decompiledJarPath)) {
			MiscHelper.copyLargeDir(fs.get().getPath("."), this.repo.getRootPath().resolve("minecraft").resolve("src"));
		}
	}

	private void copyAssets(PipelineCache pipelineCache, OrderedVersion mcVersion) throws IOException {
		if (GitCraft.config.loadAssets || GitCraft.config.loadIntegratedDatapack) {
			Path mergedJarPath = pipelineCache.getForKey(Step.STEP_MERGE);
			if (mergedJarPath == null) { // Client JAR could also work
				MiscHelper.panic("A merged JAR for version %s does not exist", mcVersion.launcherFriendlyVersionName());
			}
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mergedJarPath)) {
				if (GitCraft.config.loadAssets) {
					MiscHelper.copyLargeDir(fs.get().getPath("assets"), this.repo.getRootPath().resolve("minecraft").resolve("resources").resolve("assets"));
				}
				if (GitCraft.config.loadIntegratedDatapack) {
					MiscHelper.copyLargeDir(fs.get().getPath("data"), this.repo.getRootPath().resolve("minecraft").resolve("resources").resolve("data"));
				}
			}
		}
	}

	private void copyExternalAssets(PipelineCache pipelineCache, OrderedVersion mcVersion) throws IOException {
		if (GitCraft.config.loadAssets && GitCraft.config.loadAssetsExtern) {
			AssetsIndex assetsIndex = pipelineCache.getAssetsIndex();
			Path artifactObjectStore = pipelineCache.getForKey(STEP_FETCH_ASSETS);
			if (artifactObjectStore == null) {
				MiscHelper.panic("Assets for version %s do not exist", mcVersion.launcherFriendlyVersionName());
			}
			// Copy Assets
			Path targetRoot = this.repo.getRootPath().resolve("minecraft").resolve("external-resources").resolve("assets");
			if (GitCraft.config.useHardlinks) {
				for (Map.Entry<String, AssetsIndexMeta.AssetsIndexEntry> entry : assetsIndex.assetsIndex().objects().entrySet()) {
					Path sourcePath = GitCraft.ASSETS_OBJECTS.resolve(entry.getValue().hash());
					Path targetPath = targetRoot.resolve(entry.getKey());
					Files.createDirectories(targetPath.getParent());
					Files.createLink(targetPath, sourcePath);
				}
			} else {
				for (Map.Entry<String, AssetsIndexMeta.AssetsIndexEntry> entry : assetsIndex.assetsIndex().objects().entrySet()) {
					Path sourcePath = GitCraft.ASSETS_OBJECTS.resolve(entry.getValue().hash());
					Path targetPath = targetRoot.resolve(entry.getKey());
					Files.createDirectories(targetPath.getParent());
					Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private void createCommit(OrderedVersion mcVersion) throws GitAPIException {
		// Remove removed files from index
		this.repo.getGit().add().addFilepattern(".").setRenormalize(false).setUpdate(true).call();
		// Stage new files
		this.repo.getGit().add().addFilepattern(".").setRenormalize(false).call();
		Date version_date = new Date(OffsetDateTime.parse(mcVersion.timestamp()).toInstant().toEpochMilli());
		PersonIdent author = new PersonIdent(GitCraft.config.gitUser, GitCraft.config.gitMail, version_date, TimeZone.getTimeZone("UTC"));
		this.repo.getGit().commit().setMessage(mcVersion.toCommitMessage()).setAuthor(author).setCommitter(author).setSign(false).call();
	}

	private static final class CommitMsgFilter extends RevFilter {
		String msg;

		CommitMsgFilter(String msg) {
			this.msg = msg;
		}

		@Override
		public boolean include(RevWalk walker, RevCommit c) {
			return Objects.equals(c.getFullMessage(), this.msg);
		}

		@Override
		public RevFilter clone() {
			return this;
		}

		@Override
		public String toString() {
			return "MSG FILTER";
		}
	}
}
