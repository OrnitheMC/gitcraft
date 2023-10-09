package net.ornithemc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.github.winplay02.MappingHelper;

import dex.mcgitmaker.GitCraft;
import dex.mcgitmaker.data.McVersion;
import dex.mcgitmaker.loom.Remapper;
import net.ornithemc.nester.nest.Nests;

public class Nester {
	public static Path nestedPath(McVersion mcVersion, MappingHelper.MappingFlavour mappingFlavour) {
		return GitCraft.REMAPPED.resolve(String.format("%s-%s-nested.jar", mcVersion.version, mappingFlavour.toString()));
	}

	public static Path doNest(McVersion mcVersion, MappingHelper.MappingFlavour mappingFlavour) throws IOException {
		Path output = nestedPath(mcVersion, mappingFlavour);

		if (!output.toFile().exists() || output.toFile().length() == 22 /* empty jar */) {
			if (output.toFile().exists()) {
				output.toFile().delete();
			}

			Path input = Remapper.remappedPath(mcVersion, mappingFlavour);
			Optional<Path> nestsPath = NestHelper.getNestsPath(mcVersion, mappingFlavour);

			if (nestsPath.isPresent()) {
				Nests nests = Nests.of(nestsPath.get());
				net.ornithemc.nester.Nester.nestJar(new net.ornithemc.nester.Nester.Options(), input, output, nests);
			} else {
				Files.copy(input, output);
			}
		}

		return output;
	}
}
