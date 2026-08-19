package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GifLibraryTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private File folder;
	private GifLibrary library;

	@Before
	public void setUp() throws IOException
	{
		folder = temporaryFolder.newFolder("login-screen-gifs");
		library = new GifLibrary(folder);
	}

	@Test
	public void listsGifsWhateverTheyAreCalled() throws IOException
	{
		writeGif(folder, "zebra.gif");
		writeGif(folder, "Apple.gif");
		writeGif(folder, "a wizard did it.gif");

		List<File> files = library.list();

		assertEquals(3, files.size());
		// Sorted case-insensitively, so the panel order matches the cycling order.
		assertEquals("a wizard did it.gif", files.get(0).getName());
		assertEquals("Apple.gif", files.get(1).getName());
		assertEquals("zebra.gif", files.get(2).getName());
	}

	@Test
	public void skipsFilesThatAreNotGifs() throws IOException
	{
		writeGif(folder, "real.gif");
		// A renamed video, which would otherwise stall the decoder.
		Files.write(new File(folder, "fake.gif").toPath(), "not a gif at all".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(folder, "notes.txt").toPath(), "GIF89a".getBytes(StandardCharsets.US_ASCII));

		List<File> files = library.list();

		assertEquals(1, files.size());
		assertEquals("real.gif", files.get(0).getName());
	}

	@Test
	public void listsNothingWhenTheFolderIsMissing()
	{
		GifLibrary missing = new GifLibrary(new File(folder, "not-created-yet"));

		assertTrue(missing.list().isEmpty());
	}

	@Test
	public void addKeepsTheOriginalNameAndDoesNotOverwrite() throws IOException
	{
		File source = temporaryFolder.newFile("holiday.gif");
		writeGifBytes(source);

		assertEquals("holiday.gif", library.add(source).getName());
		assertEquals("holiday (2).gif", library.add(source).getName());
		assertEquals("holiday (3).gif", library.add(source).getName());
		assertEquals(3, library.list().size());
	}

	@Test(expected = IOException.class)
	public void addRejectsSomethingThatIsNotAGif() throws IOException
	{
		File source = temporaryFolder.newFile("clip.gif");
		Files.write(source.toPath(), "still not a gif".getBytes(StandardCharsets.US_ASCII));

		library.add(source);
	}

	@Test
	public void resolveFindsAFileInTheFolder() throws IOException
	{
		writeGif(folder, "background.gif");

		assertNotNull(library.resolve("background.gif"));
	}

	@Test
	public void resolveRefusesToEscapeTheFolder() throws IOException
	{
		File outside = temporaryFolder.newFile("outside.gif");
		writeGifBytes(outside);

		assertNull(library.resolve("../outside.gif"));
		assertNull(library.resolve(outside.getAbsolutePath()));
		assertNull(library.resolve(""));
		assertNull(library.resolve(null));
	}

	@Test
	public void removeDeletesOnlyFilesInsideTheFolder() throws IOException
	{
		writeGif(folder, "gone.gif");
		File outside = temporaryFolder.newFile("kept.gif");
		writeGifBytes(outside);

		assertTrue(library.remove("gone.gif"));
		assertFalse(library.remove("../kept.gif"));
		assertTrue(outside.exists());
	}

	@Test
	public void sanitiseStripsPathsAndForcesTheExtension()
	{
		assertEquals("evil.gif", GifLibrary.sanitise("../../evil.gif"));
		assertEquals("plain.gif", GifLibrary.sanitise("plain"));
		assertEquals("login.gif", GifLibrary.sanitise(""));
		assertEquals("keep me.gif", GifLibrary.sanitise("keep me.GIF").toLowerCase());
	}

	private static void writeGif(File folder, String name) throws IOException
	{
		writeGifBytes(new File(folder, name));
	}

	/** A GIF89a header is all the library checks, and all the tests need. */
	private static void writeGifBytes(File file) throws IOException
	{
		Files.write(file.toPath(), "GIF89a-payload".getBytes(StandardCharsets.US_ASCII));
	}
}
