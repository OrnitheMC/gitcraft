package com.github.winplay02;

import dex.mcgitmaker.GitCraft;
import dex.mcgitmaker.data.McVersion;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.impl.game.minecraft.McVersionLookup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MinecraftVersionGraph implements Iterable<McVersion> {

	private static TreeMap<String, String> semverCache = null;

	private MinecraftVersionGraph() {
	}

	private MinecraftVersionGraph(MinecraftVersionGraph previous, Predicate<Map.Entry<SemanticVersion, McVersion>> predicate, String... tags) {
		this.repoTags = new HashSet<>(previous.repoTags);
		this.overriddenEdges = new HashMap<>(previous.overriddenEdges);
		this.versions = previous.versions.entrySet().stream().filter(predicate)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> {
				MiscHelper.panic("Duplicate keys in tree map");
				return null;
			}, () -> new TreeMap<>(VERSION_COMPARATOR)));
		this.nonLinearVersions = previous.nonLinearVersions.entrySet().stream().filter(predicate)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> {
				MiscHelper.panic("Duplicate keys in tree map");
				return null;
			}, () -> new TreeMap<>(VERSION_COMPARATOR)));
		this.repoTags.addAll(Arrays.asList(tags));
		reconnectGraph(previous);
	}

	private void reconnectGraph(MinecraftVersionGraph previous) {
		McVersion root = this.getRootVersion();
		for (McVersion version : this) {
			if (this.getPreviousNode(version).isEmpty() && !version.equals(root)) {
				McVersion nearest_previous_node = previous.getPreviousNode(version).orElse(null);
				if (nearest_previous_node == null) {
					MiscHelper.panic("Previous graph was already not properly connected");
				}
				while (!this.containsVersion(nearest_previous_node)) {
					nearest_previous_node = previous.getPreviousNode(nearest_previous_node).orElse(null);
				}
				this.overriddenEdges.put(version, nearest_previous_node);
			}
		}
		testGraphConnectivity();
	}

	private boolean testGraphConnectivity() {
		McVersion root = this.getRootVersion();
		for (McVersion version : this) {
			if (this.getPreviousNode(version).isEmpty() && !version.equals(root)) {
				MiscHelper.panic("Version %s (%s) is not connected any other version in the graph", version.version, version.loaderVersion);
				return false;
			}
		}
		return true;
	}

	protected static final Pattern LINEAR_SNAPSHOT_REGEX = Pattern.compile("(^\\d\\dw\\d\\d[a-z]$)|(^\\d.\\d+(.\\d+)?(-(pre|rc)\\d+|_[a-z_\\-]+snapshot-\\d+| Pre-Release \\d+)?$)");

	public static boolean isVersionNonLinearSnapshot(McVersion mcVersion) {
		return mcVersion.snapshot && (Objects.equals(mcVersion.version, "15w14a") || (mcVersion.previousVersion == null && !LINEAR_SNAPSHOT_REGEX.matcher(mcVersion.version).matches())); // mark 15w14a explicit as april fools snapshot, since this case should not be covered by the regex
	}

	public static String fixupSemver(String proposedSemVer) {
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

	private static void loadSemverCache() {
		if (semverCache == null) {
			Path cachePath = GitCraft.CURRENT_WORKING_DIRECTORY.resolve("semver-cache.json");
			if (cachePath.toFile().exists()) {
				try {
					semverCache = SerializationHelper.deserialize(SerializationHelper.fetchAllFromPath(cachePath), SerializationHelper.TYPE_TREE_MAP_STRING_STRING);
				} catch (IOException e) {
					semverCache = new TreeMap<>();
					MiscHelper.println("This is not a fatal error: %s", e);
				}
			} else {
				semverCache = new TreeMap<>();
			}
		}
	}

	public static void lookupLoaderVersion(McVersion mcVersion) {
		// retrieve semver version from details instead of loader
		if (mcVersion.loaderVersion == null) {
			if (mcVersion.normalizedVersion == null) {
				MiscHelper.println("no normalized version present in details for %s", mcVersion.version);
			} else {
				mcVersion.loaderVersion = mcVersion.normalizedVersion;
				MiscHelper.println("Semver found for: %s as %s", mcVersion.version, mcVersion.loaderVersion);
			}
			return;
		}
		// FIX until fabric-loader is updated
		{
			if (GitCraftConfig.minecraftVersionSemVerOverride.containsKey(mcVersion.version)) {
				mcVersion.loaderVersion = GitCraftConfig.minecraftVersionSemVerOverride.get(mcVersion.version);
				return;
			}
		}
		// END FIX
		if (mcVersion.loaderVersion == null) {
			loadSemverCache();
			if (semverCache.containsKey(mcVersion.version)) {
				try {
					SemanticVersion.parse(semverCache.get(mcVersion.version));
					mcVersion.loaderVersion = semverCache.get(mcVersion.version);
					return;
				} catch (VersionParsingException ignored) {
				}
			}
			net.fabricmc.loader.impl.game.minecraft.McVersion x = null;
			Path x_path = null;
			while (x == null) {
				try {
					x_path = mcVersion.artifacts.clientJar().fetchArtifact().toPath();
					x = McVersionLookup.getVersion(List.of(x_path), mcVersion.mainClass, null);
					break;
				} catch (Exception ignored) {
				}
				try {
					x_path = mcVersion.artifacts.clientJar().fetchArtifact().toPath();
					x = McVersionLookup.getVersion(List.of(x_path), null, mcVersion.version);
					break;
				} catch (Exception e) {
					MiscHelper.println("Semver lookup failed (for %s). Retrying... ", mcVersion.version);
					MiscHelper.sleep(250);
				}
			}
			mcVersion.loaderVersion = fixupSemver(Objects.equals(x.getNormalized(), "client") ? mcVersion.version : x.getNormalized());
			if (mcVersion.version.equalsIgnoreCase("23w13a_or_b_original")) { // support extra original
				mcVersion.loaderVersion = "1.20-alpha.23.13.ab.original";
			}
			MiscHelper.println("Semver mapped for: %s as %s", x.getRaw(), mcVersion.loaderVersion);
		}
	}

	// TODO write to JSON
	public static String findPreviousBaseNodeVersionNameForNonLinearVersionString(McVersion mcVersion) {
		switch (mcVersion.loaderVersion) {
			// Combat
			case "1.14.3-rc.4.combat.1" -> {
				return "1.14.3-rc.4";
			}
			case "1.14.5-combat.2" -> {
				return "1.14.4";
			}
			case "1.14.5-combat.3" -> {
				return "1.14.5-combat.2";
			}
			case "1.15-rc.3.combat.4" -> {
				return "1.15-rc.3";
			}
			case "1.15.2-rc.2.combat.5" -> {
				return "1.15.2-rc.2";
			}
			case "1.16.2-beta.3.combat.6" -> {
				return "1.16.2-beta.3";
			}
			case "1.16.3-combat.7" -> {
				return "1.16.2";
			}
			case "1.16.3-combat.7.b" -> {
				return "1.16.3-combat.7";
			}
			case "1.16.3-combat.7.c" -> {
				return "1.16.3-combat.7.b";
			}
			case "1.16.3-combat.8" -> {
				return "1.16.3-combat.7.c";
			}
			case "1.16.3-combat.8.b" -> {
				return "1.16.3-combat.8";
			}
			case "1.16.3-combat.8.c" -> {
				return "1.16.3-combat.8.b";
			}
			case "1.0.0-rc.2+1" -> {
				return "1.0.0-rc.1";
			}
			case "1.2.0-alpha.12.5.a+1354" -> {
				return "1.2.0-alpha.12.4.a";
			}
			case "1.3.0-pre+07261249" -> {
				return "1.3.0-alpha.12.30.e";
			}
			case "1.4.0-pre" -> {
				return "1.4.0-alpha.12.42.b";
			}
			case "1.4.1-pre+10231538" -> {
				return "1.4.0-pre";
			}
			case "1.4.3-pre" -> {
				return "1.4.2";
			}
			case "1.5.0-alpha.13.3.a+1538" -> {
				return "1.5.0-alpha.13.2.b";
			}
			case "1.5.0-alpha.13.5.a+1504" -> {
				return "1.5.0-alpha.13.4.a";
			}
			case "1.5.0-alpha.13.5.a+1538" -> {
				return "1.5.0-alpha.13.5.a+1504";
			}
			case "1.5.0-alpha.13.6.a+1559" -> {
				return "1.5.0-alpha.13.5.b";
			}
			case "1.5.0-alpha.13.6.a+1636" -> {
				return "1.5.0-alpha.13.6.a+1559";
			}
			case "1.5.1-alpha.13.12.a" -> {
				return "1.5.1-alpha.13.11.a";
			}
			case "1.6.0-alpha.13.16.a+04192037" -> {
				return "1.5.2";
			}
			case "1.6.0-alpha.13.16.b+04232151" -> {
				return "1.6.0-alpha.13.16.a+04192037";
			}
			case "1.6.0-alpha.13.23.b+06080101" -> {
				return "1.6.0-alpha.13.23.a";
			}
			case "1.6.0-pre+06251516" -> {
				return "1.6.0-alpha.13.26.a";
			}
			case "1.6.2+091847" -> {
				return "1.6.1";
			}
			case "1.6.3-pre+171231" -> {
				return "1.6.2+091847";
			}
			case "1.7.0-alpha.13.36.a+09051446" -> {
				return "1.6.4";
			}
			case "1.7.0-alpha.13.36.b+09061310" -> {
				return "1.7.0-alpha.13.36.a+09051446";
			}
			case "1.7.0-alpha.13.41.b+1523" -> {
				return "1.7.0-alpha.13.41.a";
			}
			case "1.7.0-pre" -> {
				return "1.7.0-alpha.13.43.a";
			}
			case "1.7.1-pre" -> {
				return "1.7.0-pre";
			}
			case "1.7.3-pre" -> {
				return "1.7.3-alpha.13.49.a";
			}
			case "1.8.0-alpha.14.4.b+1554" -> {
				return "1.8.0-alpha.14.3.b";
			}
			case "1.8.0-alpha.14.27.b+07021646" -> {
				return "1.8.0-alpha.14.27.a";
			}
			case "1.8.0-alpha.14.34.c+08191549" -> {
				return "1.8.0-alpha.14.34.b";
			}
			case "1.11.1-alpha.16.50.a+1438" -> {
				return "1.11.0";
			}
			case "1.12.0-pre.3+1316" -> {
				return "1.12.0-pre.2";
			}
			case "1.12.0-pre.3+1409" -> {
				return "1.12.0-pre.3+1316";
			}
			// April
			case "1.8.4-alpha.15.14.a+loveandhugs" -> {
				return "1.8.3";
			}
			case "1.8.4-april.fools" -> {
				return "1.8.3";
			}
			case "1.9.2-rv+trendy" -> {
				return "1.9.2";
			}
			case "1.9.3-april.fools" -> {
				return "1.9.2";
			}
			case "1.14-alpha.19.13.shareware" -> {
				return "1.14-alpha.19.13.b";
			}
			case "1.16-alpha.20.13.inf" -> {
				return "1.16-alpha.20.13.b";
			}
			case "1.19-alpha.22.13.oneblockatatime" -> {
				return "1.19-alpha.22.13.a";
			}
			case "1.20-alpha.23.13.ab" -> {
				return "1.20-alpha.23.13.a";
			}
			case "1.20-alpha.23.13.ab.original" -> {
				return "1.20-alpha.23.13.a";
			}
			default -> {
				return null;
			}
		}
	}

	// fixes issues where SemanticVersionImpl doesn't take the build key into account in its compareTo method
	private static final Comparator<SemanticVersion> VERSION_COMPARATOR = (v1, v2) -> {
		int c = v1.compareTo((Version) v2);
		if (c == 0) {
			String b1 = v1.getBuildKey().orElse("");
			String b2 = v2.getBuildKey().orElse("");
			return b1.compareTo(b2);
		}
		return c;
	};

	public HashSet<String> repoTags = new HashSet<>();
	public TreeMap<SemanticVersion, McVersion> versions = new TreeMap<>(VERSION_COMPARATOR);
	public TreeMap<SemanticVersion, McVersion> nonLinearVersions = new TreeMap<>(VERSION_COMPARATOR);
	public HashMap<McVersion, McVersion> overriddenEdges = new HashMap<>();

	protected static SemanticVersion parseFromLoaderVersion(String loaderVersion) {
		try {
			return SemanticVersion.parse(loaderVersion);
		} catch (VersionParsingException e) {
			throw new RuntimeException(e);
		}
	}

	public static MinecraftVersionGraph createFromMetadata(LinkedHashMap<String, McVersion> metadata) {
		MinecraftVersionGraph graph = new MinecraftVersionGraph();
		for (McVersion version : metadata.values()) {
			lookupLoaderVersion(version);
			if (MinecraftVersionGraph.isVersionNonLinearSnapshot(version)) {
				graph.nonLinearVersions.put(parseFromLoaderVersion(version.loaderVersion), version);
			} else {
				graph.versions.put(parseFromLoaderVersion(version.loaderVersion), version);
			}
		}
		graph.testGraphConnectivity();
		try {
			graph.writeSemverCache();
		} catch (IOException e) {
			MiscHelper.println("This is not a fatal error: %s", e);
		}
		return graph;
	}

	public MinecraftVersionGraph filterMapping(MappingHelper.MappingFlavour mappingFlavour, MappingHelper.MappingFlavour[] mappingFallback) {
		return new MinecraftVersionGraph(this, (entry -> mappingFlavour.doMappingsExist(entry.getValue()) || (mappingFallback != null && mappingFallback.length > 0 && Arrays.stream(mappingFallback).anyMatch(mapping -> mapping.doMappingsExist(entry.getValue())))));
	}

	public MinecraftVersionGraph filterMainlineVersions() {
		MinecraftVersionGraph graph = new MinecraftVersionGraph(this, (_entry -> true));
		graph.nonLinearVersions.clear();
		return graph;
	}

	public MinecraftVersionGraph filterMinVersion(McVersion version) {
		SemanticVersion subjectVersion = parseFromLoaderVersion(version.loaderVersion);
		return new MinecraftVersionGraph(this, (entry -> entry.getKey().compareTo((Version) subjectVersion) >= 0), String.format("min-%s", version.version));
	}

	public MinecraftVersionGraph filterOnlyVersion(McVersion... version) {
		List<McVersion> versionList = Arrays.asList(version);
		return new MinecraftVersionGraph(this, (entry -> versionList.contains(entry.getValue())), versionList.stream().map((mcv) -> mcv.version).collect(Collectors.joining("-")));
	}

	public MinecraftVersionGraph filterExcludeVersion(McVersion... version) {
		List<McVersion> versionList = Arrays.asList(version);
		if (versionList.isEmpty()) {
			return this;
		}
		return new MinecraftVersionGraph(this, (entry -> !versionList.contains(entry.getValue())), "exclude-" + versionList.stream().map((mcv) -> mcv.version).collect(Collectors.joining("-")));
	}

	public MinecraftVersionGraph filterStableRelease() {
		return new MinecraftVersionGraph(this, (entry -> !entry.getValue().snapshot), "stable");
	}

	public MinecraftVersionGraph filterSnapshots() {
		return new MinecraftVersionGraph(this, (entry -> entry.getValue().snapshot), "snapshot");
	}

	public McVersion getRootVersion() {
		Optional<McVersion> rootVersion = stream().findFirst();
		if (rootVersion.isEmpty()) {
			MiscHelper.panic("MinecraftVersionGraph does not contain a root version node");
		}
		return rootVersion.get();
	}

	public Optional<McVersion> getPreviousNode(McVersion version) {
		if (overriddenEdges.containsKey(version)) {
			return Optional.of(overriddenEdges.get(version));
		}
		//if (version.previousVersion != null) {
		//	return Optional.ofNullable(getMinecraftVersionByName(version.previousVersion));
		//}
		if (!isVersionNonLinearSnapshot(version)) {
			return Optional.ofNullable(versions.lowerEntry(parseFromLoaderVersion(version.loaderVersion))).map(Map.Entry::getValue);
		}
		return Optional.ofNullable(getMinecraftVersionByLoaderName(findPreviousBaseNodeVersionNameForNonLinearVersionString(version)));
	}

	public McVersion getMinecraftMainlineVersionByName(String version_name) {
		if (version_name == null) {
			return null;
		}
		for (McVersion value : versions.values()) {
			if (value.version.equalsIgnoreCase(version_name)) {
				return value;
			}
		}
		return null;
	}

	public McVersion getMinecraftVersionByName(String version_name) {
		if (version_name == null) {
			return null;
		}
		for (McVersion value : versions.values()) {
			if (value.version.equalsIgnoreCase(version_name)) {
				return value;
			}
		}
		for (McVersion value : nonLinearVersions.values()) {
			if (value.version.equalsIgnoreCase(version_name)) {
				return value;
			}
		}
		return null;
	}

	public McVersion getMinecraftVersionByLoaderName(String loader_name) {
		if (loader_name == null) {
			return null;
		}
		for (McVersion value : versions.values()) {
			if (value.loaderVersion.equalsIgnoreCase(loader_name)) {
				return value;
			}
		}
		for (McVersion value : nonLinearVersions.values()) {
			if (value.loaderVersion.equalsIgnoreCase(loader_name)) {
				return value;
			}
		}
		return null;
	}

	public boolean containsVersion(McVersion version) {
		return version != null && this.stream().anyMatch((graphVersion) -> graphVersion.equals(version));
	}

	public String repoTagsIdentifier(MappingHelper.MappingFlavour mappingFlavour, MappingHelper.MappingFlavour[] mappingFallback) {
		List<String> sortedTags = new ArrayList<>();
		sortedTags.add(mappingFlavour.toString());
		if (mappingFallback != null && mappingFallback.length > 0) {
			sortedTags.add(String.format("fallback-%s", Arrays.stream(mappingFallback).map(Object::toString).collect(Collectors.joining("-"))));
		}
		sortedTags.addAll(this.repoTags.stream().filter(tag -> !tag.equals(mappingFlavour.toString())).toList());
		return String.join("-", sortedTags);
	}

	public Stream<McVersion> stream() {
		return Stream.concat(versions.values().stream(), nonLinearVersions.values().stream());
	}

	@Override
	public Iterator<McVersion> iterator() {
		return stream().iterator();
	}

	public void writeSemverCache() throws IOException {
		Map<String, String> semverCache = new TreeMap<>();
		for (McVersion version : this) {
			semverCache.put(version.version, version.loaderVersion);
		}
		SerializationHelper.writeAllToPath(GitCraft.CURRENT_WORKING_DIRECTORY.resolve("semver-cache.json"), SerializationHelper.serialize(semverCache));
	}
}
