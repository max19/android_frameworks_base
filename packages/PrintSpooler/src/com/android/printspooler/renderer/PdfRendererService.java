/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.printspooler.renderer;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.util.Log;
import android.view.View;
import libcore.io.IoUtils;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Service for rendering PDF documents in an isolated process.
 */
public final class PdfRendererService extends Service {
    private static final String LOG_TAG = "PdfRendererService";
    private static final boolean DEBUG = false;

    private static final int MILS_PER_INCH = 1000;
    private static final int POINTS_IN_INCH = 72;

    @Override
    public IBinder onBind(Intent intent) {
        return new PdfRendererImpl();
    }

    private final class PdfRendererImpl extends IPdfRenderer.Stub {
        private final Object mLock = new Object();

        private Bitmap mBitmap;
        private PdfRenderer mRenderer;

        @Override
        public int openDocument(ParcelFileDescriptor source) throws RemoteException {
            synchronized (mLock) {
                throwIfOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "openDocument()");
                }
                try {
                    mRenderer = new PdfRenderer(source);
                    return mRenderer.getPageCount();
                } catch (IOException ioe) {
                    throw new RemoteException("Cannot open file");
                }
            }
        }

        @Override
        public void renderPage(int pageIndex, int bitmapWidth, int bitmapHeight,
                PrintAttributes attributes, ParcelFileDescriptor destination) {
            FileOutputStream out = null;
            synchronized (mLock) {
                try {
                    throwIfNotOpened();

                    PdfRenderer.Page page = mRenderer.openPage(pageIndex);

                    final int srcWidthPts = page.getWidth();
                    final int srcHeightPts = page.getHeight();

                    final int dstWidthPts = pointsFromMils(
                            attributes.getMediaSize().getWidthMils());
                    final int dstHeightPts = pointsFromMils(
                            attributes.getMediaSize().getHeightMils());

                    final boolean scaleContent = mRenderer.shouldScaleForPrinting();
                    final boolean contentLandscape = !attributes.getMediaSize().isPortrait();

                    final float displayScale;
                    Matrix matrix = new Matrix();

                    if (scaleContent) {
                        displayScale = Math.min((float) bitmapWidth / srcWidthPts,
                                (float) bitmapHeight / srcHeightPts);
                    } else {
                        if (contentLandscape) {
                            displayScale = (float) bitmapHeight / dstHeightPts;
                        } else {
                            displayScale = (float) bitmapWidth / dstWidthPts;
                        }
                    }
                    matrix.postScale(displayScale, displayScale);

                    Configuration configuration = PdfRendererService.this.getResources()
                            .getConfiguration();
                    if (configuration.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                        matrix.postTranslate(bitmapWidth - srcWidthPts * displayScale, 0);
                    }

                    Margins minMargins = attributes.getMinMargins();
                    final int paddingLeftPts = pointsFromMils(minMargins.getLeftMils());
                    final int paddingTopPts = pointsFromMils(minMargins.getTopMils());
                    final int paddingRightPts = pointsFromMils(minMargins.getRightMils());
                    final int paddingBottomPts = pointsFromMils(minMargins.getBottomMils());

                    Rect clip = new Rect();
                    clip.left = (int) (paddingLeftPts * displayScale);
                    clip.top = (int) (paddingTopPts * displayScale);
                    clip.right = (int) (bitmapWidth - paddingRightPts * displayScale);
                    clip.bottom = (int) (bitmapHeight - paddingBottomPts * displayScale);

                    if (DEBUG) {
                        Log.i(LOG_TAG, "Rendering page:" + pageIndex);
                    }

                    Bitmap bitmap = getBitmapForSize(bitmapWidth, bitmapHeight);
                    page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    page.close();

                    out = new FileOutputStream(destination.getFileDescriptor());
                    bitmap.compress(Bitmap.CompressFormat.PNG, 0, out);
                } finally {
                    IoUtils.closeQuietly(out);
                    IoUtils.closeQuietly(destination);
                }
            }
        }

        @Override
        public void closeDocument() {
            synchronized (mLock) {
                throwIfNotOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "openDocument()");
                }
                mRenderer.close();
                mRenderer = null;
            }
        }

        @Override
        public void writePages(PageRange[] pages) {
            synchronized (mLock) {
                throwIfNotOpened();
                if (DEBUG) {
                    Log.i(LOG_TAG, "writePages()");
                }
                // TODO: Implement dropping undesired pages.
            }
        }

        private Bitmap getBitmapForSize(int width, int height) {
            if (mBitmap != null) {
                if (mBitmap.getWidth() == width && mBitmap.getHeight() == height) {
                    return mBitmap;
                }
                mBitmap.recycle();
            }
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            return mBitmap;
        }

        private void throwIfOpened() {
            if (mRenderer != null) {
                throw new IllegalStateException("Already opened");
            }
        }

        private void throwIfNotOpened() {
            if (mRenderer == null) {
                throw new IllegalStateException("Not opened");
            }
        }
    }

    private static int pointsFromMils(int mils) {
        return (int) (((float) mils / MILS_PER_INCH) * POINTS_IN_INCH);
    }
}
