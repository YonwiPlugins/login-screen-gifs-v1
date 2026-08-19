package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

/**
 * The folder of GIFs the plugin plays from.
 *
 * <p>Any filename is accepted: the library is simply whichever {@code .gif} files happen to be
 * in the folder, ordered by name. Nothing has to be called login.gif.</p>
 */
@Singleton
public class GifLibrary
{
	static final String FOLDER_NAME = "login-screen-gifs";
	static final String EXTENSION = ".gif";
	/** A login background has no business being larger than this, and the decoder would stall. */
	static final long MAX_FILE_BYTES = 128L * 1024L * 1024L;
	private static final byte[] MAGIC_87A = {'G', 'I', 'F', '8', '7', 'a'};
	private static final byte[] MAGIC_89A = {'G', 'I', 'F', '8', '9', 'a'};

	private final File folder;

	@Inject
	public GifLibrary()
	{
		this(new File(RuneLite.RUNELITE_DIR, FOLDER_NAME));
	}

	GifLibrary(File folder)
	{
		this.folder = folder;
	}

	public File getFolder()
	{
		return folder;
	}

	/**
	 * Creates the folder if it is missing. A failure here is treated as an empty library rather
	 * than an error, so the plugin still loads on a read-only home directory.
	 */
	public boolean ensureFolder()
	{
		return folder.isDirectory() || folder.mkdirs();
	}

	/**
	 * Every playable GIF in the folder, ordered by filename so that the list shown in the panel
	 * is the order the plugin cycles through.
	 */
	public List<File> list()
	{
		File[] found = folder.listFiles(file -> file.isFile() && hasGifExtension(file.getName()));
		if (found == null)
		{
			return Collections.emptyList();
		}

		List<File> playable = new ArrayList<>(found.length);
		for (File file : found)
		{
			if (file.length() > 0 && file.length() <= MAX_FILE_BYTES && looksLikeGif(file))
			{
				playable.add(file);
			}
		}

		playable.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
		return playable;
	}

	/**
	 * Resolves a stored filename back to a file inside the folder. Anything that escapes the
	 * folder returns null, so a hand-edited config cannot aim the plugin at other files.
	 */
	public File resolve(String fileName)
	{
		if (fileName == null || fileName.isEmpty())
		{
			return null;
		}

		File candidate = new File(folder, fileName);
		if (!candidate.isFile() || !hasGifExtension(candidate.getName()))
		{
			return null;
		}

		try
		{
			File parent = candidate.getCanonicalFile().getParentFile();
			if (parent == null || !parent.equals(folder.getCanonicalFile()))
			{
				return null;
			}
		}
		catch (IOException ex)
		{
			return null;
		}

		return candidate;
	}

	/**
	 * Copies a GIF into the folder under its own name. A clashing name gets a numeric suffix
	 * rather than quietly replacing what is already there.
	 */
	public File add(File source) throws IOException
	{
		if (source == null || !source.isFile())
		{
			throw new IOException("No such file");
		}
		if (source.length() > MAX_FILE_BYTES)
		{
			throw new IOException(source.getName() + " is larger than the "
				+ (MAX_FILE_BYTES / (1024L * 1024L)) + " MB limit");
		}
		if (!looksLikeGif(source))
		{
			throw new IOException(source.getName() + " is not a GIF");
		}
		if (!ensureFolder())
		{
			throw new IOException("Could not create " + folder);
		}

		File target = uniqueTarget(source.getName());
		Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	public boolean remove(String fileName)
	{
		File file = resolve(fileName);
		return file != null && file.delete();
	}

	private File uniqueTarget(String fileName)
	{
		String safe = sanitise(fileName);
		File target = new File(folder, safe);
		if (!target.exists())
		{
			return target;
		}

		String stem = safe.substring(0, safe.length() - EXTENSION.length());
		for (int suffix = 2; suffix < 10000; suffix++)
		{
			target = new File(folder, stem + " (" + suffix + ")" + EXTENSION);
			if (!target.exists())
			{
				return target;
			}
		}

		return new File(folder, stem + "-" + System.currentTimeMillis() + EXTENSION);
	}

	/**
	 * Strips anything that would let a dropped filename escape the folder, and guarantees the
	 * .gif extension the library scans for.
	 */
	static String sanitise(String fileName)
	{
		String name = new File(fileName).getName().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name))
		{
			name = "login";
		}
		if (!hasGifExtension(name))
		{
			name = name + EXTENSION;
		}
		return name;
	}

	static boolean hasGifExtension(String fileName)
	{
		return fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
	}

	/**
	 * Checks the GIF header rather than trusting the extension, so a renamed video or a
	 * half-copied download is rejected before the decoder ever sees it.
	 */
	static boolean looksLikeGif(File file)
	{
		if (file == null || !file.isFile())
		{
			return false;
		}

		byte[] header = new byte[6];
		try (InputStream in = Files.newInputStream(file.toPath()))
		{
			int read = 0;
			while (read < header.length)
			{
				int count = in.read(header, read, header.length - read);
				if (count < 0)
				{
					return false;
				}
				read += count;
			}
		}
		catch (IOException ex)
		{
			return false;
		}

		return Arrays.equals(header, MAGIC_87A) || Arrays.equals(header, MAGIC_89A);
	}
}
