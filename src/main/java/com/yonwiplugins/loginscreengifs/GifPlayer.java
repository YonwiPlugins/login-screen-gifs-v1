package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Decodes one GIF on a background thread and hands finished frames to the client thread.
 *
 * <p>Frames arrive as a plain {@code int[]} already scaled to the size the login screen wants,
 * so the only work left on the client thread is wrapping that array in a sprite. Decoding,
 * scaling and pixel packing all happen here, off the thread that serves the login UI. That
 * matters: the login screen handles clicks and keystrokes on the client thread, so heavy
 * per-frame work there is what makes the world switcher and the authenticator feel dead.</p>
 */
@Slf4j
class GifPlayer
{
	/** Two frames in flight covers a decode hiccup without hoarding memory. */
	static final int BUFFER_CAPACITY = 2;

	@Getter
	private final File file;
	@Getter
	private final int outputWidth;
	@Getter
	private final int outputHeight;
	@Getter
	private final ScaleMode scaleMode;

	private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(BUFFER_CAPACITY);
	private volatile boolean running;
	private ExecutorService decoder;

	GifPlayer(File file, int outputWidth, int outputHeight, ScaleMode scaleMode)
	{
		this.file = file;
		this.outputWidth = Math.max(1, outputWidth);
		this.outputHeight = Math.max(1, outputHeight);
		this.scaleMode = scaleMode;
	}

	synchronized void start()
	{
		if (running)
		{
			return;
		}

		running = true;
		decoder = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "login-screen-gifs-decoder");
			thread.setDaemon(true);
			return thread;
		});
		decoder.submit(this::decodeLoop);
	}

	synchronized void stop()
	{
		running = false;
		if (decoder != null)
		{
			decoder.shutdownNow();
			decoder = null;
		}
		frames.clear();
	}

	Frame poll()
	{
		return frames.poll();
	}

	/**
	 * Decodes the GIF over and over. The file is parsed once and rewound at the end of each
	 * pass, so looping costs nothing but a canvas reset.
	 */
	private void decodeLoop()
	{
		GifDecoder gif;
		try
		{
			gif = GifDecoder.open(file);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Could not open GIF {}", file, ex);
			return;
		}

		int decodedInFirstPass = 0;
		while (running)
		{
			int decoded = 0;
			try
			{
				// Each frame is held back one step, because whether a frame ends the loop is
				// only knowable once the following read comes back empty.
				Frame pending = null;
				GifDecoder.Frame source;
				while (running && (source = gif.nextFrame()) != null)
				{
					if (pending != null && !offer(pending))
					{
						return;
					}
					pending = new Frame(
						scaleToOutput(source.getArgb(), gif.getWidth(), gif.getHeight()),
						outputWidth,
						outputHeight,
						source.getDurationMillis(),
						false);
					decoded++;
				}

				if (pending != null && !offer(pending.asLastInLoop()))
				{
					return;
				}
			}
			catch (IOException | RuntimeException ex)
			{
				if (running)
				{
					log.warn("Could not decode GIF {}", file, ex);
				}
				return;
			}

			if (decodedInFirstPass == 0)
			{
				decodedInFirstPass = decoded;
				if (decoded == 0)
				{
					log.warn("GIF has no readable frames: {}", file);
					return;
				}
				log.debug("Playing {} ({} frames)", file.getName(), decoded);
			}

			gif.rewind();
		}
	}

	private boolean offer(Frame frame)
	{
		while (running)
		{
			if (frames.offer(frame))
			{
				return true;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5L));
		}
		return false;
	}

	/**
	 * Scales a composited GIF frame to the login screen size and packs it into the pixel layout
	 * the sprite routines expect.
	 */
	private int[] scaleToOutput(int[] argb, int sourceWidth, int sourceHeight)
	{
		BufferedImage source = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB);
		int[] sourcePixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
		System.arraycopy(argb, 0, sourcePixels, 0, Math.min(argb.length, sourcePixels.length));

		BufferedImage output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = output.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setColor(Color.BLACK);
			graphics.fillRect(0, 0, outputWidth, outputHeight);
			drawScaled(graphics, source);
		}
		finally
		{
			graphics.dispose();
		}

		return toSpritePixels(output);
	}

	private void drawScaled(Graphics2D graphics, BufferedImage source)
	{
		if (scaleMode == ScaleMode.STRETCH)
		{
			graphics.drawImage(source, 0, 0, outputWidth, outputHeight, null);
			return;
		}

		double scaleX = (double) outputWidth / source.getWidth();
		double scaleY = (double) outputHeight / source.getHeight();
		double scale = scaleMode == ScaleMode.COVER ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		graphics.drawImage(source, (outputWidth - width) / 2, (outputHeight - height) / 2, width, height, null);
	}

	/**
	 * Takes the image backing array and forces every pixel opaque. The sprite routines treat a
	 * zero pixel as transparent, so a pure black pixel would otherwise punch a hole straight
	 * through to the stock background.
	 */
	static int[] toSpritePixels(BufferedImage image)
	{
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		for (int i = 0; i < pixels.length; i++)
		{
			pixels[i] |= 0xFF000000;
		}
		return pixels;
	}

	/**
	 * Turns a GIF delay into a frame length. A delay of zero or one hundredth of a second is
	 * what authoring tools write when they mean as fast as sensible, and every browser clamps
	 * those to 100ms. Matching that is why the plugin needs no fallback FPS setting.
	 */
	static long frameDuration(int delayHundredths)
	{
		if (delayHundredths <= 1)
		{
			return 100L;
		}
		return delayHundredths * 10L;
	}

	/** One decoded frame, sized and packed ready for the client thread. */
	static final class Frame
	{
		@Getter
		private final int[] pixels;
		@Getter
		private final int width;
		@Getter
		private final int height;
		@Getter
		private final long durationMillis;
		private final boolean lastInLoop;

		Frame(int[] pixels, int width, int height, long durationMillis, boolean lastInLoop)
		{
			this.pixels = pixels;
			this.width = width;
			this.height = height;
			this.durationMillis = durationMillis;
			this.lastInLoop = lastInLoop;
		}

		boolean isLastInLoop()
		{
			return lastInLoop;
		}

		Frame asLastInLoop()
		{
			return new Frame(pixels, width, height, durationMillis, true);
		}
	}
}
