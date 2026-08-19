package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Manual diagnostic, not part of the suite. Points the real decoder at a real folder of GIFs
 * and reports what comes out.
 */
public final class RealGifDiagnostic
{
	public static void main(String[] args) throws Exception
	{
		File folder = new File(args[0]);
		try (PrintWriter out = new PrintWriter(args[1], "UTF-8"))
		{
			File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".gif"));
			if (files == null)
			{
				out.println("no folder: " + folder);
				return;
			}

			for (File file : files)
			{
				out.println("=========================================================");
				out.println(file.getName() + "  (" + (file.length() / 1024) + " KB)");
				reportImageIo(file, out);
				reportPlayer(file, out);
				out.flush();
			}
		}
	}

	private static void reportImageIo(File file, PrintWriter out)
	{
		ImageReader reader = null;
		try (ImageInputStream stream = ImageIO.createImageInputStream(file))
		{
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
			reader = readers.next();
			reader.setInput(stream, false, false);

			int declared;
			try
			{
				declared = reader.getNumImages(true);
			}
			catch (Exception ex)
			{
				declared = -1;
			}
			out.println("  ImageIO getNumImages: " + declared);
			out.println("  size: " + reader.getWidth(0) + "x" + reader.getHeight(0));

			int readable = 0;
			for (int index = 0; index < 200; index++)
			{
				try
				{
					reader.readAll(index, null);
					readable++;
				}
				catch (Throwable ex)
				{
					out.println("  readAll STOPPED at index " + index + ": "
						+ ex.getClass().getName() + ": " + ex.getMessage());
					Throwable cause = ex.getCause();
					while (cause != null)
					{
						out.println("      caused by " + cause.getClass().getName() + ": " + cause.getMessage());
						cause = cause.getCause();
					}
					StackTraceElement[] trace = ex.getStackTrace();
					for (int line = 0; line < Math.min(4, trace.length); line++)
					{
						out.println("      at " + trace[line]);
					}
					break;
				}
			}
			out.println("  frames readable via readAll: " + readable);

			// The same walk, but with reader.read() instead of readAll().
			int plainReadable = 0;
			for (int index = 0; index < 200; index++)
			{
				try
				{
					reader.read(index, null);
					plainReadable++;
				}
				catch (Throwable ex)
				{
					out.println("  read() STOPPED at index " + index + ": "
						+ ex.getClass().getName() + ": " + ex.getMessage());
					break;
				}
			}
			out.println("  frames readable via read(): " + plainReadable);
		}
		catch (Exception ex)
		{
			out.println("  ImageIO ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
		finally
		{
			if (reader != null)
			{
				reader.dispose();
			}
		}
	}

	private static void reportPlayer(File file, PrintWriter out) throws Exception
	{
		GifPlayer player = new GifPlayer(file, 765, 503, ScaleMode.COVER);
		List<Long> durations = new ArrayList<>();
		Set<Integer> fingerprints = new LinkedHashSet<>();
		try
		{
			player.start();
			long deadline = System.currentTimeMillis() + 8000L;
			while (System.currentTimeMillis() < deadline && fingerprints.size() < 12)
			{
				GifPlayer.Frame frame = player.poll();
				if (frame == null)
				{
					Thread.sleep(5L);
					continue;
				}
				durations.add(frame.getDurationMillis());
				fingerprints.add(fingerprint(frame.getPixels()));
			}
		}
		finally
		{
			player.stop();
		}

		out.println("  GifPlayer frames delivered: " + durations.size()
			+ ", distinct images: " + fingerprints.size());
		out.println("  durations: " + durations);
		if (durations.isEmpty())
		{
			out.println("  >>> NO FRAMES AT ALL");
		}
		else if (fingerprints.size() <= 1)
		{
			out.println("  >>> STATIC: every delivered frame is identical");
		}
	}

	private static int fingerprint(int[] pixels)
	{
		int hash = 17;
		for (int i = 0; i < pixels.length; i += 997)
		{
			hash = hash * 31 + pixels[i];
		}
		return hash;
	}
}
