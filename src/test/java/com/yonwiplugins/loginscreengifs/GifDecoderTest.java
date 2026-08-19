package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin carries its own GIF reader because the one in the JRE throws
 * {@code ArrayIndexOutOfBoundsException: Index 4096 out of bounds for length 4096} on plenty of
 * valid GIFs, 4096 being the LZW dictionary ceiling. These cover the reader that replaced it.
 */
public class GifDecoderTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void readsEveryFrameOfAnAnimation() throws Exception
	{
		File gif = AnimatedGifs.write(temporaryFolder.newFile("four.gif"), 8, 8, 5,
			Color.RED, Color.GREEN, Color.BLUE, Color.WHITE);

		GifDecoder decoder = GifDecoder.open(gif);

		assertEquals(8, decoder.getWidth());
		assertEquals(8, decoder.getHeight());

		List<GifDecoder.Frame> frames = drain(decoder);
		assertEquals(4, frames.size());
		// Four different colours must come back as four different images.
		assertEquals(4, distinctImages(frames));
	}

	@Test
	public void carriesTheDelayFromEachFrame() throws Exception
	{
		File gif = AnimatedGifs.write(temporaryFolder.newFile("timed.gif"), 4, 4, 5,
			Color.RED, Color.GREEN);

		for (GifDecoder.Frame frame : drain(GifDecoder.open(gif)))
		{
			assertEquals(50L, frame.getDurationMillis());
		}
	}

	@Test
	public void rewindReplaysFromTheStart() throws Exception
	{
		File gif = AnimatedGifs.write(temporaryFolder.newFile("loop.gif"), 4, 4, 5,
			Color.RED, Color.GREEN, Color.BLUE);
		GifDecoder decoder = GifDecoder.open(gif);

		List<GifDecoder.Frame> first = drain(decoder);
		assertNull("the pass should be finished", decoder.nextFrame());

		decoder.rewind();
		List<GifDecoder.Frame> second = drain(decoder);

		assertEquals(first.size(), second.size());
		assertEquals(3, second.size());
	}

	@Test
	public void aSingleFrameGifStillReads() throws Exception
	{
		File gif = AnimatedGifs.write(temporaryFolder.newFile("one.gif"), 4, 4, 5, Color.RED);

		assertEquals(1, drain(GifDecoder.open(gif)).size());
	}

	@Test(expected = Exception.class)
	public void refusesSomethingThatIsNotAGif() throws Exception
	{
		File notAGif = temporaryFolder.newFile("fake.gif");
		java.nio.file.Files.write(notAGif.toPath(), "definitely not a gif".getBytes("US-ASCII"));

		GifDecoder.open(notAGif);
	}

	/**
	 * The end-to-end check that would have caught the original bug: the player must keep
	 * producing genuinely different frames, not the same one over and over.
	 */
	@Test
	public void thePlayerKeepsProducingDifferentFrames() throws Exception
	{
		File gif = AnimatedGifs.write(temporaryFolder.newFile("player.gif"), 16, 16, 2,
			Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN);
		GifPlayer player = new GifPlayer(gif, 32, 32, ScaleMode.STRETCH);

		Set<Integer> distinct = new LinkedHashSet<>();
		int delivered = 0;
		try
		{
			player.start();
			long deadline = System.currentTimeMillis() + 8000L;
			while (System.currentTimeMillis() < deadline && distinct.size() < 5)
			{
				GifPlayer.Frame frame = player.poll();
				if (frame == null)
				{
					Thread.sleep(5L);
					continue;
				}
				delivered++;
				distinct.add(fingerprint(frame.getPixels()));
			}
		}
		finally
		{
			player.stop();
		}

		assertTrue("no frames were delivered at all", delivered > 0);
		assertEquals("the player froze on one image", 5, distinct.size());
	}

	@Test
	public void thePlayerMarksTheEndOfALoop() throws Exception
	{
		File gif = AnimatedGifs.write(temporaryFolder.newFile("loopflag.gif"), 8, 8, 2,
			Color.RED, Color.GREEN, Color.BLUE);
		GifPlayer player = new GifPlayer(gif, 8, 8, ScaleMode.STRETCH);

		boolean sawLoopEnd = false;
		try
		{
			player.start();
			long deadline = System.currentTimeMillis() + 8000L;
			while (System.currentTimeMillis() < deadline && !sawLoopEnd)
			{
				GifPlayer.Frame frame = player.poll();
				if (frame == null)
				{
					Thread.sleep(5L);
					continue;
				}
				sawLoopEnd = frame.isLastInLoop();
			}
		}
		finally
		{
			player.stop();
		}

		assertTrue("cycling on a loop boundary needs this flag", sawLoopEnd);
	}

	private static List<GifDecoder.Frame> drain(GifDecoder decoder) throws Exception
	{
		List<GifDecoder.Frame> frames = new ArrayList<>();
		GifDecoder.Frame frame;
		while ((frame = decoder.nextFrame()) != null)
		{
			assertNotNull(frame.getArgb());
			frames.add(frame);
		}
		return frames;
	}

	private static int distinctImages(List<GifDecoder.Frame> frames)
	{
		Set<Integer> seen = new LinkedHashSet<>();
		for (GifDecoder.Frame frame : frames)
		{
			seen.add(fingerprint(frame.getArgb()));
		}
		return seen.size();
	}

	private static int fingerprint(int[] pixels)
	{
		int hash = 17;
		for (int pixel : pixels)
		{
			hash = hash * 31 + pixel;
		}
		return hash;
	}
}
