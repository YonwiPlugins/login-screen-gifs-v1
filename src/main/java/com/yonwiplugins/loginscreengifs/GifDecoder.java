package com.yonwiplugins.loginscreengifs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A self-contained GIF reader.
 *
 * <p>The JRE ships one, but it throws {@code ArrayIndexOutOfBoundsException: Index 4096 out of
 * bounds for length 4096} on a good number of perfectly valid GIFs — 4096 being the ceiling of
 * the LZW dictionary. Its decoder walks off the end of its own string table when a stream fills
 * the dictionary without sending a clear code first, which is exactly what the encoders behind
 * Giphy, Tenor and ffmpeg tend to produce. Such a GIF either yields nothing at all or stops a
 * few frames in.</p>
 *
 * <p>So the format is parsed here instead. The LZW loop below stops growing its table at the
 * ceiling rather than running past it, which is the whole of the difference.</p>
 */
final class GifDecoder
{
	/** 2^12: an LZW code is at most 12 bits, so the dictionary cannot exceed this. */
	private static final int MAX_STACK_SIZE = 4096;
	private static final int DISPOSAL_RESTORE_BACKGROUND = 2;
	private static final int DISPOSAL_RESTORE_PREVIOUS = 3;

	private static final int EXTENSION_INTRODUCER = 0x21;
	private static final int IMAGE_SEPARATOR = 0x2C;
	private static final int TRAILER = 0x3B;
	private static final int GRAPHIC_CONTROL_LABEL = 0xF9;

	private final byte[] data;
	private int position;

	private int width;
	private int height;
	private int[] globalColorTable;
	private int backgroundColour;
	private int firstFramePosition;

	// Compositing state, carried between frames.
	private int[] canvas;
	private int[] previousCanvas;
	private int pendingDisposal;
	private int pendingLeft;
	private int pendingTop;
	private int pendingWidth;
	private int pendingHeight;

	// Set by the graphic control extension ahead of each frame.
	private int delayHundredths;
	private int transparentIndex = -1;
	private int disposalMethod;

	private final byte[] block = new byte[256];

	private GifDecoder(byte[] data)
	{
		this.data = data;
	}

	static GifDecoder open(File file) throws IOException
	{
		byte[] bytes = Files.readAllBytes(file.toPath());
		GifDecoder decoder = new GifDecoder(bytes);
		decoder.readHeader();
		return decoder;
	}

	int getWidth()
	{
		return width;
	}

	int getHeight()
	{
		return height;
	}

	private void readHeader() throws IOException
	{
		if (data.length < 13)
		{
			throw new IOException("Truncated GIF");
		}

		String signature = new String(data, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
		if (!"GIF87a".equals(signature) && !"GIF89a".equals(signature))
		{
			throw new IOException("Not a GIF: " + signature);
		}

		position = 6;
		width = readShort();
		height = readShort();
		int packed = readByte();
		int backgroundIndex = readByte();
		readByte(); // pixel aspect ratio, unused

		if (width <= 0 || height <= 0)
		{
			throw new IOException("GIF has no size: " + width + "x" + height);
		}

		if ((packed & 0x80) != 0)
		{
			globalColorTable = readColorTable(2 << (packed & 0x07));
			backgroundColour = backgroundIndex < globalColorTable.length
				? globalColorTable[backgroundIndex]
				: 0xFF000000;
		}
		else
		{
			backgroundColour = 0xFF000000;
		}

		firstFramePosition = position;
		canvas = new int[width * height];
		java.util.Arrays.fill(canvas, 0xFF000000);
	}

	/** Restarts playback from the first frame without re-reading the file. */
	void rewind()
	{
		position = firstFramePosition;
		pendingDisposal = 0;
		transparentIndex = -1;
		delayHundredths = 0;
		disposalMethod = 0;
		java.util.Arrays.fill(canvas, 0xFF000000);
	}

	/**
	 * Reads the next frame, composited onto the running canvas.
	 *
	 * @return the frame, or null once the GIF has no more
	 */
	Frame nextFrame() throws IOException
	{
		while (position < data.length)
		{
			int blockType = readByte();
			switch (blockType)
			{
				case IMAGE_SEPARATOR:
					return readImage();
				case EXTENSION_INTRODUCER:
					readExtension();
					break;
				case TRAILER:
					return null;
				default:
					// Junk between blocks: skip it rather than giving up on the file.
					break;
			}
		}
		return null;
	}

	private void readExtension() throws IOException
	{
		int label = readByte();
		if (label == GRAPHIC_CONTROL_LABEL)
		{
			readByte(); // block size, always 4
			int packed = readByte();
			disposalMethod = (packed >> 2) & 0x07;
			delayHundredths = readShort();
			int transparent = readByte();
			transparentIndex = (packed & 0x01) != 0 ? transparent : -1;
			readByte(); // block terminator
		}
		else
		{
			skipSubBlocks();
		}
	}

	private Frame readImage() throws IOException
	{
		int left = readShort();
		int top = readShort();
		int frameWidth = readShort();
		int frameHeight = readShort();
		int packed = readByte();

		boolean hasLocalTable = (packed & 0x80) != 0;
		boolean interlaced = (packed & 0x40) != 0;
		int[] colourTable = hasLocalTable
			? readColorTable(2 << (packed & 0x07))
			: globalColorTable;

		if (colourTable == null)
		{
			throw new IOException("GIF frame has no colour table");
		}

		// Apply the previous frame's disposal before drawing over it.
		applyPendingDisposal();

		byte[] indices = decodeImageData(frameWidth * frameHeight);
		drawFrame(indices, colourTable, left, top, frameWidth, frameHeight, interlaced);

		pendingDisposal = disposalMethod;
		pendingLeft = left;
		pendingTop = top;
		pendingWidth = frameWidth;
		pendingHeight = frameHeight;
		if (pendingDisposal == DISPOSAL_RESTORE_PREVIOUS)
		{
			if (previousCanvas == null || previousCanvas.length != canvas.length)
			{
				previousCanvas = new int[canvas.length];
			}
			System.arraycopy(canvas, 0, previousCanvas, 0, canvas.length);
		}

		Frame frame = new Frame(canvas.clone(), GifPlayer.frameDuration(delayHundredths));
		// Defaults reset per frame; a GIF need not repeat the control extension.
		transparentIndex = -1;
		delayHundredths = 0;
		disposalMethod = 0;
		return frame;
	}

	private void applyPendingDisposal()
	{
		if (pendingDisposal == DISPOSAL_RESTORE_BACKGROUND)
		{
			fillRect(pendingLeft, pendingTop, pendingWidth, pendingHeight, backgroundColour);
		}
		else if (pendingDisposal == DISPOSAL_RESTORE_PREVIOUS && previousCanvas != null)
		{
			System.arraycopy(previousCanvas, 0, canvas, 0, canvas.length);
		}
		pendingDisposal = 0;
	}

	private void fillRect(int left, int top, int rectWidth, int rectHeight, int colour)
	{
		for (int y = Math.max(0, top); y < Math.min(height, top + rectHeight); y++)
		{
			int row = y * width;
			for (int x = Math.max(0, left); x < Math.min(width, left + rectWidth); x++)
			{
				canvas[row + x] = colour;
			}
		}
	}

	private void drawFrame(byte[] indices, int[] colourTable, int left, int top,
		int frameWidth, int frameHeight, boolean interlaced)
	{
		for (int row = 0; row < frameHeight; row++)
		{
			int targetRow = top + (interlaced ? interlacedRow(row, frameHeight) : row);
			if (targetRow < 0 || targetRow >= height)
			{
				continue;
			}

			int sourceOffset = row * frameWidth;
			int targetOffset = targetRow * width;
			for (int column = 0; column < frameWidth; column++)
			{
				int x = left + column;
				if (x < 0 || x >= width)
				{
					continue;
				}

				int index = indices[sourceOffset + column] & 0xFF;
				if (index == transparentIndex)
				{
					// Transparent pixels leave whatever the previous frame put there.
					continue;
				}
				if (index < colourTable.length)
				{
					canvas[targetOffset + x] = colourTable[index];
				}
			}
		}
	}

	/** GIF interlacing stores rows in four passes. */
	private static int interlacedRow(int row, int frameHeight)
	{
		int pass1 = (frameHeight + 7) / 8;
		int pass2 = pass1 + (frameHeight + 3) / 8;
		int pass3 = pass2 + (frameHeight + 1) / 4;

		if (row < pass1)
		{
			return row * 8;
		}
		if (row < pass2)
		{
			return (row - pass1) * 8 + 4;
		}
		if (row < pass3)
		{
			return (row - pass2) * 4 + 2;
		}
		return (row - pass3) * 2 + 1;
	}

	/**
	 * LZW decompression.
	 *
	 * <p>The one guard the JRE decoder is missing is on {@code available}: once the dictionary
	 * reaches 4096 entries it must stop growing and simply keep decoding with the table it has,
	 * rather than writing past the end of it.</p>
	 */
	private byte[] decodeImageData(int pixelCount) throws IOException
	{
		byte[] pixels = new byte[pixelCount];
		int dataSize = readByte();
		if (dataSize < 1 || dataSize > 11)
		{
			throw new IOException("Bad LZW code size: " + dataSize);
		}

		int clearCode = 1 << dataSize;
		int endOfInformation = clearCode + 1;
		int available = clearCode + 2;
		int codeSize = dataSize + 1;
		int codeMask = (1 << codeSize) - 1;

		short[] prefix = new short[MAX_STACK_SIZE];
		byte[] suffix = new byte[MAX_STACK_SIZE];
		byte[] pixelStack = new byte[MAX_STACK_SIZE + 1];

		for (int code = 0; code < clearCode; code++)
		{
			suffix[code] = (byte) code;
		}

		int datum = 0;
		int bits = 0;
		int count = 0;
		int blockIndex = 0;
		int first = 0;
		int top = 0;
		int written = 0;
		int oldCode = -1;

		while (written < pixelCount)
		{
			if (top == 0)
			{
				if (bits < codeSize)
				{
					if (count == 0)
					{
						count = readSubBlock();
						if (count <= 0)
						{
							break;
						}
						blockIndex = 0;
					}
					datum += (block[blockIndex] & 0xFF) << bits;
					bits += 8;
					blockIndex++;
					count--;
					continue;
				}

				int code = datum & codeMask;
				datum >>= codeSize;
				bits -= codeSize;

				if (code == endOfInformation || code > available)
				{
					break;
				}

				if (code == clearCode)
				{
					codeSize = dataSize + 1;
					codeMask = (1 << codeSize) - 1;
					available = clearCode + 2;
					oldCode = -1;
					continue;
				}

				if (oldCode == -1)
				{
					pixelStack[top++] = suffix[code];
					oldCode = code;
					first = code;
					continue;
				}

				int inCode = code;
				if (code == available)
				{
					pixelStack[top++] = (byte) first;
					code = oldCode;
				}

				while (code > clearCode && top < MAX_STACK_SIZE)
				{
					pixelStack[top++] = suffix[code];
					code = prefix[code];
				}
				first = suffix[code] & 0xFF;
				pixelStack[top++] = (byte) first;

				// Here is the fix: grow the dictionary only while there is room for it.
				if (available < MAX_STACK_SIZE)
				{
					prefix[available] = (short) oldCode;
					suffix[available] = (byte) first;
					available++;
					if ((available & codeMask) == 0 && available < MAX_STACK_SIZE)
					{
						codeSize++;
						codeMask += available;
					}
				}
				oldCode = inCode;
			}

			top--;
			pixels[written++] = pixelStack[top];
		}

		skipSubBlocks();
		return pixels;
	}

	private int[] readColorTable(int entries) throws IOException
	{
		if (position + entries * 3 > data.length)
		{
			throw new IOException("Truncated colour table");
		}

		int[] table = new int[entries];
		for (int entry = 0; entry < entries; entry++)
		{
			int red = data[position++] & 0xFF;
			int green = data[position++] & 0xFF;
			int blue = data[position++] & 0xFF;
			table[entry] = 0xFF000000 | (red << 16) | (green << 8) | blue;
		}
		return table;
	}

	/** Reads one sub-block into {@link #block}, returning its length. */
	private int readSubBlock()
	{
		if (position >= data.length)
		{
			return 0;
		}

		int size = data[position++] & 0xFF;
		int available = Math.min(size, data.length - position);
		System.arraycopy(data, position, block, 0, available);
		position += available;
		return available;
	}

	private void skipSubBlocks()
	{
		while (position < data.length)
		{
			int size = data[position++] & 0xFF;
			if (size == 0)
			{
				return;
			}
			position = Math.min(data.length, position + size);
		}
	}

	private int readByte()
	{
		return position < data.length ? data[position++] & 0xFF : 0;
	}

	private int readShort()
	{
		return readByte() | (readByte() << 8);
	}

	/** One composited frame, the full size of the GIF canvas. */
	static final class Frame
	{
		private final int[] argb;
		private final long durationMillis;

		Frame(int[] argb, long durationMillis)
		{
			this.argb = argb;
			this.durationMillis = durationMillis;
		}

		int[] getArgb()
		{
			return argb;
		}

		long getDurationMillis()
		{
			return durationMillis;
		}
	}
}
