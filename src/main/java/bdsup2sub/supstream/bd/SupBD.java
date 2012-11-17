/*
 * Copyright 2012 Volker Oth (0xdeadbeef) / Miklos Juhasz (mjuhasz)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bdsup2sub.supstream.bd;

import bdsup2sub.bitmap.Bitmap;
import bdsup2sub.bitmap.Palette;
import bdsup2sub.core.*;
import bdsup2sub.supstream.*;
import bdsup2sub.tools.FileBuffer;
import bdsup2sub.tools.FileBufferException;
import bdsup2sub.utils.ToolBox;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading and writing of Blu-Ray captions demuxed from M2TS transport streams (BD-SUP).
 */
public class SupBD implements SubtitleStream {

    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.getInstance();

    private FileBuffer buffer;
    private List<SubPictureBD> subPictures = new ArrayList<SubPictureBD>();
    private int forcedFrameCount;

    /** color palette of the last decoded caption  */
    private Palette palette;
    /** bitmap of the last decoded caption  */
    private Bitmap bitmap;
    /** index of dominant color for the current caption  */
    private int primaryColorIndex;

    public SupBD(String filename) throws CoreException {
        SupBDParser parser = new SupBDParser(filename);
        buffer = parser.getBuffer();
        subPictures = parser.getSubPictures();
        forcedFrameCount = parser.getForcedFrameCount();
    }

    /**
     * Decode caption from the input stream.
     * @param pic SubPicture object containing info about the caption
     * @param transIdx index of the transparent color
     * @return bitmap of the decoded caption
     * @throws CoreException
     */
    private Bitmap decodeImage(SubPictureBD pic, int transIdx) throws CoreException {
        int w = pic.getImageWidth();
        int h = pic.getImageHeight();
        // always decode image obj 0, start with first entry in fragment list
        ImageObjectFragment info = pic.getImageObject().getFragmentList().get(0);
        long startOfs = info.getImageBufferOfs();

        if (w > pic.getWidth() || h > pic.getHeight()) {
            throw new CoreException("Subpicture too large: " + w + "x" + h + " at offset " + ToolBox.toHexLeftZeroPadded(startOfs, 8));
        }

        Bitmap bm = new Bitmap(w, h, (byte)transIdx);

        int b;
        int index = 0;
        int ofs = 0;
        int size;
        int xpos = 0;

        try {
            // just for multi-packet support, copy all of the image data in one common buffer
            byte buf[] = new byte[pic.getImageObject().getBufferSize()];
            index = 0;
            for (int p = 0; p < pic.getImageObject().getFragmentList().size(); p++) {
                // copy data of all packet to one common buffer
                info = pic.getImageObject().getFragmentList().get(p);
                for (int i=0; i < info.getImagePacketSize(); i++) {
                    buf[index+i] = (byte)buffer.getByte(info.getImageBufferOfs()+i);
                }
                index += info.getImagePacketSize();
            }

            index = 0;

            do {
                b = buf[index++]&0xff;
                if (b == 0) {
                    b = buf[index++]&0xff;
                    if (b == 0) {
                        // next line
                        ofs = (ofs/w)*w;
                        if (xpos < w) {
                            ofs+=w;
                        }
                        xpos = 0;
                    } else {
                        if ( (b & 0xC0) == 0x40) {
                            // 00 4x xx -> xxx zeroes
                            size = ((b-0x40)<<8)+(buf[index++]&0xff);
                            for (int i=0; i < size; i++) {
                                bm.getInternalBuffer()[ofs++] = 0; /*(byte)b;*/
                            }
                            xpos += size;
                        } else if ((b & 0xC0) == 0x80) {
                            // 00 8x yy -> x times value y
                            size = (b-0x80);
                            b = buf[index++]&0xff;
                            for (int i=0; i<size; i++) {
                                bm.getInternalBuffer()[ofs++] = (byte)b;
                            }
                            xpos += size;
                        } else if  ((b & 0xC0) != 0) {
                            // 00 cx yy zz -> xyy times value z
                            size = ((b - 0xC0) << 8) + (buf[index++] & 0xff);
                            b = buf[index++] & 0xff;
                            for (int i=0; i < size; i++) {
                                bm.getInternalBuffer()[ofs++] = (byte)b;
                            }
                            xpos += size;
                        }  else {
                            // 00 xx -> xx times 0
                            for (int i=0; i < b; i++) {
                                bm.getInternalBuffer()[ofs++] = 0;
                            }
                            xpos += b;
                        }
                    }
                } else {
                    bm.getInternalBuffer()[ofs++] = (byte)b;
                    xpos++;
                }
            } while (index < buf.length);

            return bm;
        } catch (FileBufferException ex) {
            throw new CoreException(ex.getMessage());
        } catch (ArrayIndexOutOfBoundsException ex) {
            logger.warn("problems during RLE decoding of picture OBJ at offset " + ToolBox.toHexLeftZeroPadded(startOfs + index, 8) + "\n");
            return bm;
        }
    }

    /**
     * decode palette from the input stream
     * @param pic SubPicture object containing info about the current caption
     * @return
     * @throws CoreException
     */
    private Palette decodePalette(SubPictureBD pic) throws CoreException {
        boolean fadeOut = false;
        int palIndex;
        List<PaletteInfo> pl = pic.getPalettes().get(pic.getImageObject().getPaletteID());
        if (pl == null) {
            throw new CoreException("Palette ID out of bounds.");
        }

        Palette palette = new Palette(256, Core.usesBT601());
        // by definition, index 0xff is always completely transparent
        // also all entries must be fully transparent after initialization

        try {
            for (PaletteInfo p : pl) {
                int index = p.getPaletteOffset();
                for (int i = 0; i < p.getPaletteSize(); i++) {
                    // each palette entry consists of 5 bytes
                    palIndex = buffer.getByte(index);
                    int y = buffer.getByte(++index);
                    int cr, cb;
                    if (configuration.isSwapCrCb()) {
                        cb = buffer.getByte(++index);
                        cr = buffer.getByte(++index);
                    } else {
                        cr = buffer.getByte(++index);
                        cb = buffer.getByte(++index);
                    }
                    int alpha = buffer.getByte(++index);

                    int alphaOld = palette.getAlpha(palIndex);
                    // avoid fading out
                    if (alpha >= alphaOld) {
                        if (alpha < configuration.getAlphaCrop()) {// to not mess with scaling algorithms, make transparent color black
                            y = 16;
                            cr = 128;
                            cb = 128;
                        }
                        palette.setAlpha(palIndex, alpha);
                    } else {
                        fadeOut = true;
                    }

                    palette.setYCbCr(palIndex, y, cb, cr);
                    index++;
                }
            }
            if (fadeOut) {
                logger.warn("fade out detected -> patched palette\n");
            }
            return palette;
        } catch (FileBufferException ex) {
            throw new CoreException(ex.getMessage());
        }
    }

    /**
     * decode given picture
     * @param pic SubPicture object containing info about caption
     * @throws CoreException
     */
    private void decode(SubPictureBD pic)  throws CoreException {
        palette = decodePalette(pic);
        bitmap  = decodeImage(pic, palette.getIndexOfMostTransparentPaletteEntry());
        primaryColorIndex = bitmap.getPrimaryColorIndex(palette.getAlpha(), configuration.getAlphaThreshold(), palette.getY());
    }

    /* (non-Javadoc)
     * @see SubtitleStream#decode(int)
     */
    public void decode(int index) throws CoreException {
        if (index < subPictures.size()) {
            decode(subPictures.get(index));
        } else {
            throw new CoreException("Index "+index+" out of bounds\n");
        }
    }

    /* setters / getters */

    /* (non-Javadoc)
     * @see SubtitleStream#getPalette()
     */
    public Palette getPalette() {
        return palette;
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getBitmap()
     */
    public Bitmap getBitmap() {
        return bitmap;
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getImage()
     */
    public BufferedImage getImage() {
        return bitmap.getImage(palette.getColorModel());
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getImage(Bitmap)
     */
    public BufferedImage getImage(Bitmap bm) {
        return bm.getImage(palette.getColorModel());
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getPrimaryColorIndex()
     */
    public int getPrimaryColorIndex() {
        return primaryColorIndex;
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getSubPicture(int)
     */
    public SubPicture getSubPicture(int index) {
        return subPictures.get(index);
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getNumFrames()
     */
    public int getFrameCount() {
        return subPictures.size();
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getForcedFrameCount()
     */
    public int getForcedFrameCount() {
        return forcedFrameCount;
    }

    /* (non-Javadoc)
     * @see SubtitleStream#close()
     */
    public void close() {
        if (buffer != null) {
            buffer.close();
        }
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getEndTime(int)
     */
    public long getEndTime(int index) {
        return subPictures.get(index).getEndTime();
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getStartTime(int)
     */
    public long getStartTime(int index) {
        return subPictures.get(index).getStartTime();
    }

    /* (non-Javadoc)
     * @see SubtitleStream#isForced(int)
     */
    public boolean isForced(int index) {
        return subPictures.get(index).isForced();
    }

    /* (non-Javadoc)
     * @see SubtitleStream#getStartOffset(int)
     */
    public long getStartOffset(int index) {
        SubPictureBD pic = subPictures.get(index);
        return pic.getImageObject().getFragmentList().get(0).getImageBufferOfs();
    }

    /**
     * Get frame rate for given caption
     * @param index index of caption
     * @return frame rate
     */
    public double getFps(int index) {
        return Framerate.valueForId(subPictures.get(index).getType());
    }
}

