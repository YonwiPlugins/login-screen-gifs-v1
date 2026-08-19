package com.yonwiplugins.loginscreengifs;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.w3c.dom.Node;

/**
 * Writes real multi-frame GIFs for the tests. The plugin was only ever exercised against a
 * single frame image, which is exactly why animation could break unnoticed.
 */
final class AnimatedGifs
{
	private static final String IMAGE_METADATA = "javax_imageio_gif_image_1.0";

	private AnimatedGifs()
	{
	}

	/**
	 * @param delayHundredths per-frame delay in hundredths of a second, as GIFs store it
	 */
	static File write(File file, int width, int height, int delayHundredths, Color... colours) throws IOException
	{
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(file))
		{
			writer.setOutput(output);
			writer.prepareWriteSequence(null);

			for (Color colour : colours)
			{
				BufferedImage frame = solid(width, height, colour);
				ImageWriteParam params = writer.getDefaultWriteParam();
				IIOMetadata metadata = writer.getDefaultImageMetadata(
					new ImageTypeSpecifier(frame), params);
				applyDelay(metadata, delayHundredths);
				writer.writeToSequence(new IIOImage(frame, null, metadata), params);
			}

			writer.endWriteSequence();
		}
		finally
		{
			writer.dispose();
		}
		return file;
	}

	private static void applyDelay(IIOMetadata metadata, int delayHundredths) throws IOException
	{
		Node root = metadata.getAsTree(IMAGE_METADATA);
		IIOMetadataNode control = child(root, "GraphicControlExtension");
		control.setAttribute("disposalMethod", "none");
		control.setAttribute("userInputFlag", "FALSE");
		control.setAttribute("transparentColorFlag", "FALSE");
		control.setAttribute("transparentColorIndex", "0");
		control.setAttribute("delayTime", Integer.toString(delayHundredths));
		metadata.setFromTree(IMAGE_METADATA, root);
	}

	private static IIOMetadataNode child(Node root, String name)
	{
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
		{
			if (name.equalsIgnoreCase(node.getNodeName()))
			{
				return (IIOMetadataNode) node;
			}
		}

		IIOMetadataNode created = new IIOMetadataNode(name);
		root.appendChild(created);
		return created;
	}

	private static BufferedImage solid(int width, int height, Color colour)
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
		return image;
	}
}
