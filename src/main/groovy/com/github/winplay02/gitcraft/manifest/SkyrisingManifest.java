package com.github.winplay02.gitcraft.manifest;

import com.github.winplay02.gitcraft.GitCraftConfig;
import com.github.winplay02.gitcraft.meta.VersionMeta;
import com.github.winplay02.gitcraft.types.Artifact;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.GitCraftPaths;
import com.github.winplay02.gitcraft.util.MiscHelper;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.game.minecraft.McVersion;
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class SkyrisingManifest extends ManifestProvider {
	public SkyrisingManifest() throws IOException {
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

	private String fixupSemver(String proposedSemVer) {
		if (Objects.equals(proposedSemVer, "1.19-22.w.13.oneBlockAtATime")) {
			return "1.19-alpha.22.13.oneblockatatime";
		}
		if (Objects.equals(proposedSemVer, "1.16.2-Combat.Test.8")) { // this is wrong here, fabric gets it correct
			return "1.16.3-combat.8";
		}
		if (Objects.equals(proposedSemVer, "0.30.1.c")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.30.1-c";
		}
		if (Objects.equals(proposedSemVer, "0.0.13.a")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.13-a";
		}
		if (Objects.equals(proposedSemVer, "0.0.13.a.3")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.13-a3";
		}
		if (Objects.equals(proposedSemVer, "0.0.11.a")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.11-a";
		}
		if (Objects.equals(proposedSemVer, "rd-161348")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.0.161348-rd";
		}
		if (Objects.equals(proposedSemVer, "rd-160052")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.0.160052-rd";
		}
		if (Objects.equals(proposedSemVer, "rd-20090515")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.0.132328-rd20090515";
		}
		if (Objects.equals(proposedSemVer, "rd-132328")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.0.132328-rd";
		}
		if (Objects.equals(proposedSemVer, "rd-132211")) { // this might be correct, but semver parser from fabric loader does not accept it
			return "0.0.0.132211-rd";
		}
		if (proposedSemVer.contains("-Experimental")) {
			return proposedSemVer.replace("-Experimental", "-alpha.0.0.Experimental");
		}
		return proposedSemVer;
	}

	@Override
	protected String lookupSemanticVersion(VersionMeta versionMeta) {
		{
			if (GitCraftConfig.minecraftVersionSemVerOverride.containsKey(versionMeta.id())) {
				return GitCraftConfig.minecraftVersionSemVerOverride.get(versionMeta.id());
			}
		}
		if (semverCache.containsKey(versionMeta.id())) {
			try {
				SemanticVersion.parse(semverCache.get(versionMeta.id()));
				return semverCache.get(versionMeta.id());
			} catch (VersionParsingException ignored) {
			}
		}

		Artifact clientJar = OrderedVersion.getClientJarFromMeta(versionMeta);
		McVersion lookedUpVersion = null;
		try {
			lookedUpVersion = McVersionLookup.getVersion(/*clientJarPath != null ? List.of(clientJarPath) : */Collections.emptyList(), versionMeta.mainClass(), null);
			SemanticVersion.parse(lookedUpVersion.getNormalized());
		} catch (Exception | AssertionError ignored1) {
			try {
				lookedUpVersion = McVersionLookup.getVersion(/*clientJarPath != null ? List.of(clientJarPath) : */Collections.emptyList(), null, versionMeta.id());
				SemanticVersion.parse(lookedUpVersion.getNormalized());
			} catch (Exception | AssertionError ignored2) {
				Path clientJarPath = null;
				if (clientJar != null) {
					clientJar.fetchArtifact(clientJarArtifactParentPath(versionMeta), "client jar");
					clientJarPath = clientJar.resolve(clientJarArtifactParentPath(versionMeta));
				}
				lookedUpVersion = McVersionLookup.getVersion(clientJarPath != null ? List.of(clientJarPath) : Collections.emptyList(), versionMeta.mainClass(), null);
			}
		}
		String lookedUpSemver = fixupSemver(Objects.equals(lookedUpVersion.getNormalized(), "client") ? versionMeta.id() : lookedUpVersion.getNormalized());
		try {
			SemanticVersion.parse(lookedUpVersion.getNormalized());
		} catch (VersionParsingException e) {
			MiscHelper.panicBecause(e, "Exhausted every option of getting a semantic version from %s. It seems like this version needs a manual override. Please report if this issue occurrs.", versionMeta.id());
		}
		MiscHelper.println("Semver mapped for: %s as %s", lookedUpVersion.getRaw(), lookedUpSemver);
		return lookedUpSemver;
	}

	private Path clientJarArtifactParentPath(VersionMeta versionMeta) {
		return GitCraftPaths.MC_VERSION_STORE.resolve(versionMeta.id());
	}

	@Override
	public List<String> getParentVersion(OrderedVersion mcVersion) {
		switch (mcVersion.semanticVersion()) {
			// Combat
			case "1.14.3-rc.4.combat.1" -> {
				return List.of("1.14.3-rc.4");
			}
			case "1.14.5-combat.2" -> {
				return List.of("1.14.4");
			}
			case "1.14.5-combat.3" -> {
				return List.of("1.14.5-combat.2");
			}
			case "1.15-rc.3.combat.4" -> {
				return List.of("1.15-rc.3");
			}
			case "1.15.2-rc.2.combat.5" -> {
				return List.of("1.15.2-rc.2");
			}
			case "1.16.2-beta.3.combat.6" -> {
				return List.of("1.16.2-beta.3");
			}
			case "1.16.3-combat.7.a" -> {
				return List.of("1.16.2");
			}
			case "1.16.3-combat.7.b" -> {
				return List.of("1.16.3-combat.7.a");
			}
			case "1.16.3-combat.7.c" -> {
				return List.of("1.16.3-combat.7.b");
			}
			case "1.16.3-combat.8.b" -> {
				return List.of("1.16.3-combat.7.c");
			}
			case "1.16.3-combat.8.c" -> {
				return List.of("1.16.3-combat.8.b");
			}
			case "1.0.0-rc.2+1" -> {
				return List.of("1.0.0-rc.1");
			}
			case "1.2.0-alpha.12.5.a+1354" -> {
				return List.of("1.2.0-alpha.12.4.a");
			}
			case "1.3.1" -> {
				return List.of("1.3.0-pre+07261249");
			}
			case "1.3.0-pre+07261249" -> {
				return List.of("1.3.0-alpha.12.30.e");
			}
			case "1.4.0-pre" -> {
				return List.of("1.4.0-alpha.12.42.b");
			}
			case "1.4.1-pre+10231538" -> {
				return List.of("1.4.0-pre");
			}
			case "1.4.3-pre" -> {
				return List.of("1.4.2");
			}
			case "1.5.0-alpha.13.3.a+1538" -> {
				return List.of("1.5.0-alpha.13.2.b");
			}
			case "1.5.0-alpha.13.5.a+1504" -> {
				return List.of("1.5.0-alpha.13.4.a");
			}
			case "1.5.0-alpha.13.5.a+1538" -> {
				return List.of("1.5.0-alpha.13.5.a+1504");
			}
			case "1.5.0-alpha.13.6.a+1559" -> {
				return List.of("1.5.0-alpha.13.5.b");
			}
			case "1.5.0-alpha.13.6.a+1636" -> {
				return List.of("1.5.0-alpha.13.6.a+1559");
			}
			case "1.5.1-alpha.13.12.a" -> {
				return List.of("1.5.1-alpha.13.11.a");
			}
			case "1.6.0-alpha.13.16.a+04192037" -> {
				return List.of("1.5.2");
			}
			case "1.6.0-alpha.13.16.b+04232151" -> {
				return List.of("1.6.0-alpha.13.16.a+04192037");
			}
			case "1.6.0-alpha.13.23.b+06080101" -> {
				return List.of("1.6.0-alpha.13.23.a");
			}
			case "1.6.0-pre+06251516" -> {
				return List.of("1.6.0-alpha.13.26.a");
			}
			case "1.6.2+091847" -> {
				return List.of("1.6.1");
			}
			case "1.6.3-pre+171231" -> {
				return List.of("1.6.2+091847");
			}
			case "1.7.0-alpha.13.36.a+09051446" -> {
				return List.of("1.6.4");
			}
			case "1.7.0-alpha.13.36.b+09061310" -> {
				return List.of("1.7.0-alpha.13.36.a+09051446");
			}
			case "1.7.0-alpha.13.41.b+1523" -> {
				return List.of("1.7.0-alpha.13.41.a");
			}
			case "1.7.0-pre" -> {
				return List.of("1.7.0-alpha.13.43.a");
			}
			case "1.7.1-pre" -> {
				return List.of("1.7.0-pre");
			}
			case "1.7.3-pre" -> {
				return List.of("1.7.3-alpha.13.49.a");
			}
			case "1.8.0-alpha.14.4.b+1554" -> {
				return List.of("1.8.0-alpha.14.3.b");
			}
			case "1.8.0-alpha.14.27.b+07021646" -> {
				return List.of("1.8.0-alpha.14.27.a");
			}
			case "1.8.0-alpha.14.34.c+08191549" -> {
				return List.of("1.8.0-alpha.14.34.b");
			}
			case "1.11.1-alpha.16.50.a+1438" -> {
				return List.of("1.11.0");
			}
			case "1.12.0-pre.3+1316" -> {
				return List.of("1.12.0-pre.2");
			}
			case "1.12.0-pre.3+1409" -> {
				return List.of("1.12.0-pre.3+1316");
			}
			// April
			case "1.8.4-alpha.15.14.a+loveandhugs" -> {
				return List.of("1.8.3");
			}
			case "1.8.4-april.fools" -> {
				return List.of("1.8.3");
			}
			case "1.9.2-rv+trendy" -> {
				return List.of("1.9.2");
			}
			case "1.9.3-april.fools" -> {
				return List.of("1.9.2");
			}
			case "1.14-alpha.19.13.shareware" -> {
				return List.of("1.14-alpha.19.13.b");
			}
			case "1.16-alpha.20.13.inf" -> {
				return List.of("1.16-alpha.20.13.b");
			}
			case "1.19-alpha.22.13.oneblockatatime" -> {
				return List.of("1.19-alpha.22.13.a");
			}
			case "1.20-alpha.23.13.ab" -> {
				return List.of("1.20-alpha.23.13.a");
			}
			case "1.20-alpha.23.13.ab.original" -> {
				return List.of("1.20-alpha.23.13.a");
			}
			default -> {
				return null;
			}

		}
	}
}
