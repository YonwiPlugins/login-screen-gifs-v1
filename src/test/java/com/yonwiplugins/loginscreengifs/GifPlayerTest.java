package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GifPlayerTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	/**
	 * The reason the plugin has no fallback FPS setting: a delay of zero or one hundredth of a
	 * second means "as fast as sensible", and browsers settle those at 100ms.
	 */
	@Test
	public void clampsTheDelaysThatAuthoringToolsLeaveUnset()
	{
		assertEquals(100L, GifPlayer.frameDuration(0));
		assertEquals(100L, GifPlayer.frameDuration(1));
	}

	@Test
	public void honoursARealDelay()
	{
		assertEquals(20L, GifPlayer.frameDuration(2));
		assertEquals(50L, GifPlayer.frameDuration(5));
		assertEquals(100L, GifPlayer.frameDuration(10));
		assertEquals(1000L, GifPlayer.frameDuration(100));
	}

	@Test
	public void forcesEveryPixelOpaqueSoBlackIsNotTransparent()
	{
		BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x000000);
		image.setRGB(1, 0, 0x3366FF);

		int[] pixels = GifPlayer.toSpritePixels(image);

		// A zero pixel reads as transparent to the sprite routines, so black must not be zero.
		assertNotEquals(0, pixels[0]);
		assertEquals(0xFF000000, pixels[0]);
		assertEquals(0xFF3366FF, pixels[1]);
	}

	@Test
	public void decodesAGifToTheRequestedLoginScreenSize() throws Exception
	{
		File gif = writeGif(temporaryFolder.newFile("wide.gif"), 40, 20, Color.RED);
		GifPlayer player = new GifPlayer(gif, 64, 32, ScaleMode.STRETCH);

		try
		{
			player.start();
			GifPlayer.Frame frame = pollFor(player, 5000L);

			assertNotNull("decoder produced no frame", frame);
			assertEquals(64, frame.getWidth());
			assertEquals(32, frame.getHeight());
			assertEquals(64 * 32, frame.getPixels().length);
			assertTrue("a single frame GIF is the end of its own loop", frame.isLastInLoop());
		}
		finally
		{
			player.stop();
		}
	}

	@Test
	public void letterboxesRatherThanCroppingWhenContained() throws Exception
	{
		File gif = writeGif(temporaryFolder.newFile("square.gif"), 20, 20, Color.RED);
		GifPlayer player = new GifPlayer(gif, 40, 20, ScaleMode.CONTAIN);

		try
		{
			player.start();
			GifPlayer.Frame frame = pollFor(player, 5000L);
			assertNotNull("decoder produced no frame", frame);

			// A square source in a wide output leaves black bars down each side.
			assertEquals(0xFF000000, frame.getPixels()[0]);
			assertNotEquals(0xFF000000, frame.getPixels()[20 * 40 / 2 + 20]);
		}
		finally
		{
			player.stop();
		}
	}

	private static GifPlayer.Frame pollFor(GifPlayer player, long timeoutMillis) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline)
		{
			GifPlayer.Frame frame = player.poll();
			if (frame != null)
			{
				return frame;
			}
			Thread.sleep(10L);
		}
		return null;
	}

	private static File writeGif(File file, int width, int height, Color colour) throws IOException
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setColor(colour);
			graphics.fillRect(0, 0, width, height);
		}
		finally
		{
			graphics.dispose();
		}

		assertTrue("could not write a test GIF", ImageIO.write(image, "gif", file));
		return file;
	}
}
