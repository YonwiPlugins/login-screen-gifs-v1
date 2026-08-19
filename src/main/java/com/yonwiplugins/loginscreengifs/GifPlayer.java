package com.yonwiplugins.loginscreengifs;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

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
	private static final long MAX_SOURCE_BYTES = 64L * 1024L * 1024L;
	private static final String STREAM_METADATA = "javax_imageio_gif_stream_1.0";
	private static final String IMAGE_METADATA = "javax_imageio_gif_image_1.0";

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

	private void decodeLoop()
	{
		while (running)
		{
			int decoded;
			try (ImageInputStream stream = ImageIO.createImageInputStream(file))
			{
				if (stream == null)
				{
					log.warn("Could not open GIF {}", file);
					return;
				}

				decoded = decodeOnce(stream);
			}
			catch (IOException | RuntimeException ex)
			{
				if (running)
				{
					log.warn("Could not decode GIF {}", file, ex);
				}
				return;
			}

			if (decoded == 0)
			{
				log.warn("GIF has no readable frames: {}", file);
				return;
			}
		}
	}

	/**
	 * Decodes one full pass of the GIF and returns how many frames it produced. The final frame
	 * of the pass is flagged so the plugin can cycle on a loop boundary.
	 */
	private int decodeOnce(ImageInputStream stream) throws IOException
	{
		ImageReader reader = gifReader();
		if (reader == null)
		{
			log.warn("This JRE has no GIF reader");
			return 0;
		}

		try
		{
			reader.setInput(stream, false, false);
			int[] screen = logicalScreenSize(reader);
			checkSize(screen[0], screen[1], "logical screen");

			BufferedImage canvas = new BufferedImage(screen[0], screen[1], BufferedImage.TYPE_INT_ARGB);
			Graphics2D canvasGraphics = canvas.createGraphics();
			try
			{
				canvasGraphics.setComposite(AlphaComposite.Src);
				canvasGraphics.setColor(Color.BLACK);
				canvasGraphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

				// Each frame is composited onto the canvas, scaled, then queued. Offering to a
				// full queue blocks, and that is what paces this thread to playback speed.
				int index = 0;
				Frame pending = null;
				while (running)
				{
					IIOImage image;
					try
					{
						checkSize(reader.getWidth(index), reader.getHeight(index), "frame " + index);
						image = reader.readAll(index, null);
					}
					catch (IndexOutOfBoundsException ex)
					{
						break;
					}

					BufferedImage raw = (BufferedImage) image.getRenderedImage();
					FrameMetadata metadata = frameMetadata(image.getMetadata());
					BufferedImage restore = "restoreToPrevious".equals(metadata.disposal)
						? copyOf(canvas)
						: null;

					canvasGraphics.setComposite(AlphaComposite.SrcOver);
					canvasGraphics.drawImage(raw, metadata.left, metadata.top, null);
					raw.flush();

					// Each frame is held back one step so the last one can be marked as the end
					// of the loop, which is only knowable once the reader runs out.
					if (pending != null && !offer(pending))
					{
						return index;
					}
					pending = new Frame(scaleToOutput(canvas), outputWidth, outputHeight, metadata.duration, false);

					applyDisposal(canvasGraphics, restore, metadata);
					if (restore != null)
					{
						restore.flush();
					}
					index++;
				}

				if (pending != null)
				{
					offer(pending.asLastInLoop());
				}
				return index;
			}
			finally
			{
				canvasGraphics.dispose();
				canvas.flush();
			}
		}
		finally
		{
			reader.dispose();
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
	 * Scales the composited canvas to the login screen size and packs it into the pixel layout
	 * the sprite routines expect.
	 */
	private int[] scaleToOutput(BufferedImage canvas)
	{
		BufferedImage output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = output.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setColor(Color.BLACK);
			graphics.fillRect(0, 0, outputWidth, outputHeight);
			drawScaled(graphics, canvas);
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

	private static ImageReader gifReader()
	{
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
		return readers.hasNext() ? readers.next() : null;
	}

	private static int[] logicalScreenSize(ImageReader reader) throws IOException
	{
		IIOMetadata metadata = reader.getStreamMetadata();
		if (metadata != null)
		{
			Node descriptor = child(metadata.getAsTree(STREAM_METADATA), "LogicalScreenDescriptor");
			if (descriptor != null)
			{
				int width = intAttribute(descriptor, "logicalScreenWidth", 0);
				int height = intAttribute(descriptor, "logicalScreenHeight", 0);
				if (width > 0 && height > 0)
				{
					return new int[]{width, height};
				}
			}
		}
		return new int[]{reader.getWidth(0), reader.getHeight(0)};
	}

	private static void checkSize(int width, int height, String label) throws IOException
	{
		long bytes = (long) width * (long) height * Integer.BYTES;
		if (width <= 0 || height <= 0 || bytes > MAX_SOURCE_BYTES)
		{
			throw new IOException("GIF " + label + " is too large to decode safely: " + width + "x" + height);
		}
	}

	private static FrameMetadata frameMetadata(IIOMetadata metadata)
	{
		Node root = metadata.getAsTree(IMAGE_METADATA);
		Node descriptor = child(root, "ImageDescriptor");
		Node control = child(root, "GraphicControlExtension");

		return new FrameMetadata(
			descriptor == null ? 0 : intAttribute(descriptor, "imageLeftPosition", 0),
			descriptor == null ? 0 : intAttribute(descriptor, "imageTopPosition", 0),
			descriptor == null ? 0 : intAttribute(descriptor, "imageWidth", 0),
			descriptor == null ? 0 : intAttribute(descriptor, "imageHeight", 0),
			control == null ? "none" : stringAttribute(control, "disposalMethod", "none"),
			frameDuration(control == null ? 0 : intAttribute(control, "delayTime", 0)));
	}

	private static void applyDisposal(Graphics2D graphics, BufferedImage restore, FrameMetadata metadata)
	{
		if ("restoreToBackgroundColor".equals(metadata.disposal))
		{
			graphics.setComposite(AlphaComposite.Src);
			graphics.setColor(Color.BLACK);
			graphics.fillRect(metadata.left, metadata.top, metadata.width, metadata.height);
		}
		else if (restore != null)
		{
			graphics.setComposite(AlphaComposite.Src);
			graphics.drawImage(restore, 0, 0, null);
		}
	}

	private static BufferedImage copyOf(BufferedImage source)
	{
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = copy.createGraphics();
		try
		{
			graphics.setComposite(AlphaComposite.Src);
			graphics.drawImage(source, 0, 0, null);
		}
		finally
		{
			graphics.dispose();
		}
		return copy;
	}

	private static Node child(Node node, String name)
	{
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
		{
			if (name.equals(child.getNodeName()))
			{
				return child;
			}
		}
		return null;
	}

	private static int intAttribute(Node node, String name, int fallback)
	{
		String value = stringAttribute(node, name, null);
		if (value == null)
		{
			return fallback;
		}
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ex)
		{
			return fallback;
		}
	}

	private static String stringAttribute(Node node, String name, String fallback)
	{
		NamedNodeMap attributes = node.getAttributes();
		if (attributes == null)
		{
			return fallback;
		}
		Node attribute = attributes.getNamedItem(name);
		return attribute == null ? fallback : attribute.getNodeValue();
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

	private static final class FrameMetadata
	{
		private final int left;
		private final int top;
		private final int width;
		private final int height;
		private final String disposal;
		private final long duration;

		private FrameMetadata(int left, int top, int width, int height, String disposal, long duration)
		{
			this.left = left;
			this.top = top;
			this.width = width;
			this.height = height;
			this.disposal = disposal;
			this.duration = duration;
		}
	}
}
