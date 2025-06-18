/*
 * Copyright (C) 2025 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.glencoesoftware.omero.zarr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.OptionalInt;
import java.util.Random;

public class Utils {

    /**
     * Generates a grayscale image with the specified text written at a random position.
     * The background is black (0) and the text is white (255).
     *
     * @param width The width of the image
     * @param height The height of the image
     * @param text The text to write on the image
     * @return byte array containing the grayscale image data
     */
    public static byte[] generateGreyscaleImageWithText(int width, int height, String text) {
        return generateGreyscaleImageWithText(width, height, text, OptionalInt.empty(), OptionalInt.empty());
    }

    /**
     * Generates a grayscale image with the specified text written at a specified position.
     * The background is black (0) and the text is white (255).
     *
     * @param width The width of the image
     * @param height The height of the image
     * @param text The text to write on the image
     * @param posX The x position of the text
     * @param posY The y position of the text
     * @return byte array containing the grayscale image data
     */
    public static byte[] generateGreyscaleImageWithText(int width, int height, String text, OptionalInt posX, OptionalInt posY) {
        // Create buffered image
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = image.createGraphics();

        // Set rendering hints for better text quality
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        // Fill background with black
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);

        // Set up text properties
        g2d.setColor(Color.WHITE);
        int fontSize = Math.min(width, height) / 20; // Scale font size based on image dimensions
        g2d.setFont(new Font("Arial", Font.BOLD, fontSize));

        // Get text dimensions
        int textWidth = g2d.getFontMetrics().stringWidth(text);
        int textHeight = g2d.getFontMetrics().getHeight();

        // Generate random position for text, ensuring it fits within the image
        Random random = new Random();
        int x = posX.isPresent() ? posX.getAsInt() : random.nextInt(width - textWidth);
        int y = posY.isPresent() ? posY.getAsInt() : random.nextInt(height - textHeight) + g2d.getFontMetrics().getAscent();

        // Draw the text
        g2d.drawString(text, x, y);
        g2d.dispose();

        // Get the underlying byte array
        byte[] imageData = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        return imageData;
    }

}
