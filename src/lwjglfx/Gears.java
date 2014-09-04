/*
 * Copyright (c) 2002-2012 LWJGL Project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'LWJGL' nor the names of
 *   its contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package lwjglfx;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.*;
import org.lwjgl.util.stream.RenderStream;
import org.lwjgl.util.stream.StreamHandler;
import org.lwjgl.util.stream.StreamUtil;
import org.lwjgl.util.stream.StreamUtil.RenderStreamFactory;
import org.lwjgl.util.stream.StreamUtil.TextureStreamFactory;
import org.lwjgl.util.stream.TextureStream;

import java.nio.FloatBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;

import static org.lwjgl.opengl.AMDDebugOutput.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.KHRDebug.*;

/** The LWJGL Gears test, modified to use the PBO reader & writer. */
final class Gears {

	private static final float VIEW_ROT_X = 10.0f;
	private static final float VIEW_ROT_Y = 25.0f;
	private static final float VIEW_ROT_Z = 0.0f;

	static Drawable drawable;

	private final ConcurrentLinkedQueue<Runnable> pendingRunnables;

	private final Pbuffer pbuffer;
	private final int     maxSamples;

	private final ReadOnlyIntegerWrapper fps;

	private RenderStreamFactory renderStreamFactory;
	private RenderStream        renderStream;

	private TextureStreamFactory textureStreamFactory;
	private TextureStream        textureStream;

	private int gear1;
	private int gear2;
	private int gear3;

	private float angle;

	private boolean vsync = true;

	private int transfersToBuffer = 2;
	private int samples           = 1;

	private final AtomicLong snapshotRequest;
	private       long       snapshotCurrent;

	Gears(final StreamHandler readHandler, final StreamHandler writeHandler) {
		this.pendingRunnables = new ConcurrentLinkedQueue<Runnable>();

		this.fps = new ReadOnlyIntegerWrapper(this, "fps", 0);

		if ( (Pbuffer.getCapabilities() & Pbuffer.PBUFFER_SUPPORTED) == 0 )
			throw new UnsupportedOperationException("Support for pbuffers is required.");

		try {
			pbuffer = new Pbuffer(1, 1, new PixelFormat(), null, null, new ContextAttribs().withDebug(true));
			pbuffer.makeCurrent();
		} catch (LWJGLException e) {
			throw new RuntimeException(e);
		}

		Gears.drawable = pbuffer;

		final ContextCapabilities caps = GLContext.getCapabilities();

		if ( caps.OpenGL30 || (caps.GL_EXT_framebuffer_multisample && caps.GL_EXT_framebuffer_blit) )
			maxSamples = glGetInteger(GL_MAX_SAMPLES);
		else
			maxSamples = 1;

		if ( caps.GL_KHR_debug ) {
			glDebugMessageCallback(new KHRDebugCallback());
			glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DEBUG_SEVERITY_NOTIFICATION, null, false); // NV: Buffer object mallocs
			glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE, GL_DEBUG_SEVERITY_MEDIUM, null, false); // NV: Pixel-transfer synchronized with rendering
		} else if ( caps.GL_AMD_debug_output )
			glDebugMessageCallbackAMD(new AMDDebugOutputCallback());

		this.renderStreamFactory = StreamUtil.getRenderStreamImplementation();
		this.renderStream = renderStreamFactory.create(readHandler, 1, transfersToBuffer);

		this.textureStreamFactory = StreamUtil.getTextureStreamImplementation();
		this.textureStream = textureStreamFactory.create(writeHandler, transfersToBuffer);

		this.snapshotRequest = new AtomicLong();
		this.snapshotCurrent = -1L;
	}

	public int getMaxSamples() {
		return maxSamples;
	}

	public RenderStreamFactory getRenderStreamFactory() {
		return renderStreamFactory;
	}

	public void setRenderStreamFactory(final RenderStreamFactory renderStreamFactory) {
		pendingRunnables.offer(new Runnable() {
			public void run() {
				if ( renderStream != null )
					renderStream.destroy();

				Gears.this.renderStreamFactory = renderStreamFactory;

				renderStream = renderStreamFactory.create(renderStream.getHandler(), samples, transfersToBuffer);
			}
		});
	}

	public TextureStreamFactory getTextureStreamFactory() {
		return textureStreamFactory;
	}

	public void setTextureStreamFactory(final TextureStreamFactory textureStreamFactory) {
		pendingRunnables.offer(new Runnable() {
			public void run() {
				if ( textureStream != null )
					textureStream.destroy();

				Gears.this.textureStreamFactory = textureStreamFactory;

				textureStream = textureStreamFactory.create(textureStream.getHandler(), transfersToBuffer);
				updateSnapshot();
			}
		});

	}

	private void init() {
		// setup ogl
		FloatBuffer pos = BufferUtils.createFloatBuffer(4).put(new float[] { 5.0f, 5.0f, 10.0f, 0.0f });
		FloatBuffer red = BufferUtils.createFloatBuffer(4).put(new float[] { 0.8f, 0.1f, 0.0f, 1.0f });
		FloatBuffer green = BufferUtils.createFloatBuffer(4).put(new float[] { 0.0f, 0.8f, 0.2f, 1.0f });
		FloatBuffer blue = BufferUtils.createFloatBuffer(4).put(new float[] { 0.2f, 0.2f, 1.0f, 1.0f });

		pos.flip();
		red.flip();
		green.flip();
		blue.flip();

		glLight(GL_LIGHT0, GL_POSITION, pos);
		glEnable(GL_CULL_FACE);
		glEnable(GL_LIGHTING);
		glEnable(GL_LIGHT0);
		glEnable(GL_DEPTH_TEST);

		// make the gears
		gear1 = glGenLists(1);
		glNewList(gear1, GL_COMPILE);
		glMaterial(GL_FRONT, GL_AMBIENT_AND_DIFFUSE, red);
		gear(1.0f, 4.0f, 1.0f, 20, 0.7f);
		glEndList();

		gear2 = glGenLists(1);
		glNewList(gear2, GL_COMPILE);
		glMaterial(GL_FRONT, GL_AMBIENT_AND_DIFFUSE, green);
		gear(0.5f, 2.0f, 2.0f, 10, 0.7f);
		glEndList();

		gear3 = glGenLists(1);
		glNewList(gear3, GL_COMPILE);
		glMaterial(GL_FRONT, GL_AMBIENT_AND_DIFFUSE, blue);
		gear(1.3f, 2.0f, 0.5f, 10, 0.7f);
		glEndList();

		glEnable(GL_NORMALIZE);
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

		glMatrixMode(GL_PROJECTION);
		glLoadIdentity();
		glFrustum(-1.0f, 1.0f, -1.0f, 1.0f, 5.0f, 60.0f);

		glMatrixMode(GL_MODELVIEW);
		glLoadIdentity();
		glTranslatef(1.0f, -0.5f, -40.0f);
	}

	public void execute(final CountDownLatch running) {
		init();

		loop(running);

		destroy();
	}

	public ReadOnlyIntegerProperty fpsProperty() {
		return fps.getReadOnlyProperty();
	}

	private void destroy() {
		renderStream.destroy();
		textureStream.destroy();
		pbuffer.destroy();
	}

	public void updateSnapshot() {
		snapshotRequest.incrementAndGet();
	}

	public void setVsync(final boolean vsync) {
		this.vsync = vsync;
	}

	public int getTransfersToBuffer() {
		return transfersToBuffer;
	}

	public void setTransfersToBuffer(final int transfersToBuffer) {
		if ( this.transfersToBuffer == transfersToBuffer )
			return;

		this.transfersToBuffer = transfersToBuffer;
		resetStreams();
	}

	public void setSamples(final int samples) {
		if ( this.samples == samples )
			return;

		this.samples = samples;
		resetStreams();
	}

	private void resetStreams() {
		pendingRunnables.offer(new Runnable() {
			public void run() {
				textureStream.destroy();
				renderStream.destroy();

				renderStream = renderStreamFactory.create(renderStream.getHandler(), samples, transfersToBuffer);
				textureStream = textureStreamFactory.create(textureStream.getHandler(), transfersToBuffer);

				updateSnapshot();
			}
		});
	}

	private void drainPendingActionsQueue() {
		Runnable runnable;

		while ( (runnable = pendingRunnables.poll()) != null )
			runnable.run();
	}

	private void loop(final CountDownLatch running) {
		final long FPS_UPD_INTERVAL = 1 * (1000L * 1000L * 1000L);

		long nextFPSUpdateTime = System.nanoTime() + FPS_UPD_INTERVAL;
		int frames = 0;

		long lastTime = System.nanoTime();
		double timeDelta = 0.0;

		while ( 0 < running.getCount() ) {
			angle += 0.1f * timeDelta; // 0.1 degrees per ms == 100 degrees per second

			drainPendingActionsQueue();

			final long snapshotRequestID = snapshotRequest.get();
			if ( snapshotCurrent < snapshotRequestID ) {
				textureStream.snapshot();
				snapshotCurrent = snapshotRequestID;
			}
			textureStream.tick();

			renderStream.bind();

			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			glPushMatrix();
			glRotatef(VIEW_ROT_X, 1.0f, 0.0f, 0.0f);
			glRotatef(VIEW_ROT_Y, 0.0f, 1.0f, 0.0f);
			glRotatef(VIEW_ROT_Z, 0.0f, 0.0f, 1.0f);

			glDisable(GL_LIGHTING);
			glEnable(GL_TEXTURE_2D);

			textureStream.bind();
			drawQuad(textureStream.getWidth(), textureStream.getHeight());
			glBindTexture(GL_TEXTURE_2D, 0);

			glDisable(GL_TEXTURE_2D);
			glEnable(GL_LIGHTING);

			//for ( int i = -4; i < 4; i++ )
			int i = 0;
			{
				glPushMatrix();
				glTranslatef(-3.0f, -2.0f, i);
				glRotatef(angle, 0.0f, 0.0f, 1.0f);
				glCallList(gear1);
				glPopMatrix();

				glPushMatrix();
				glTranslatef(3.1f, -2.0f, i);
				glRotatef(-2.0f * angle - 9.0f, 0.0f, 0.0f, 1.0f);
				glCallList(gear2);
				glPopMatrix();

				glPushMatrix();
				glTranslatef(-3.1f, 4.2f, i);
				glRotatef(-2.0f * angle - 25.0f, 0.0f, 0.0f, 1.0f);
				glCallList(gear3);
				glPopMatrix();
			}

			glPopMatrix();

			renderStream.swapBuffers();

			if ( vsync )
				Display.sync(60);

			final long currentTime = System.nanoTime();
			timeDelta = (currentTime - lastTime) / 1000000.0;
			lastTime = currentTime;

			frames++;
			if ( nextFPSUpdateTime <= currentTime ) {
				long timeUsed = FPS_UPD_INTERVAL + (currentTime - nextFPSUpdateTime);
				nextFPSUpdateTime = currentTime + FPS_UPD_INTERVAL;

				final int fpsAverage = (int)(frames * (1000L * 1000L * 1000L) / (timeUsed));
				Platform.runLater(new Runnable() {
					public void run() {
						Gears.this.fps.set(fpsAverage);
					}
				});
				frames = 0;
			}
		}
	}

	private static void drawQuad(final int width, final int height) {
		final float ratio = (float)width / height;

		final float SIZE = 16.0f;

		final float quadW;
		final float quadH;

		if ( ratio <= 1.0f ) {
			quadH = SIZE;
			quadW = quadH * ratio;
		} else {
			quadW = SIZE;
			quadH = quadW * ratio;
		}

		glPushMatrix();

		glTranslatef(-quadW * 0.5f, -quadH * 0.5f, -4.0f);
		glBegin(GL_QUADS);
		{
			glTexCoord2f(0.0f, 1.0f);
			glVertex2f(0.0f, 0.0f);

			glTexCoord2f(1.0f, 1.0f);
			glVertex2f(quadW, 0.0f);

			glTexCoord2f(1.0f, 0.0f);
			glVertex2f(quadW, quadH);

			glTexCoord2f(0.0f, 0.0f);
			glVertex2f(0.0f, quadH);
		}
		glEnd();
		glPopMatrix();
	}

	private static float sin(float value) {
		return (float)Math.sin(value);
	}

	private static float cos(float value) {
		return (float)Math.cos(value);
	}

	/**
	 * Draw a gear wheel.  You'll probably want to call this function when
	 * building a display list since we do a lot of trig here.
	 *
	 * @param inner_radius radius of hole at center
	 * @param outer_radius radius at center of teeth
	 * @param width        width of gear
	 * @param teeth        number of teeth
	 * @param tooth_depth  depth of tooth
	 */
	private static void gear(float inner_radius, float outer_radius, float width, int teeth, float tooth_depth) {
		int i;
		float r0, r1, r2;
		float angle, da;
		float u, v, len;

		r0 = inner_radius;
		r1 = outer_radius - tooth_depth / 2.0f;
		r2 = outer_radius + tooth_depth / 2.0f;

		da = 2.0f * (float)Math.PI / teeth / 4.0f;

		glShadeModel(GL_FLAT);

		glNormal3f(0.0f, 0.0f, 1.0f);

		/* draw front face */
		glBegin(GL_QUAD_STRIP);
		for ( i = 0; i <= teeth; i++ ) {
			angle = i * 2.0f * (float)Math.PI / teeth;
			glVertex3f(r0 * cos(angle), r0 * sin(angle), width * 0.5f);
			glVertex3f(r1 * cos(angle), r1 * sin(angle), width * 0.5f);
			if ( i < teeth ) {
				glVertex3f(r0 * cos(angle), r0 * sin(angle), width * 0.5f);
				glVertex3f(r1 * cos(angle + 3.0f * da), r1 * sin(angle + 3.0f * da), width * 0.5f);
			}
		}
		glEnd();

		/* draw front sides of teeth */
		glBegin(GL_QUADS);
		for ( i = 0; i < teeth; i++ ) {
			angle = i * 2.0f * (float)Math.PI / teeth;
			glVertex3f(r1 * cos(angle), r1 * sin(angle), width * 0.5f);
			glVertex3f(r2 * cos(angle + da), r2 * sin(angle + da), width * 0.5f);
			glVertex3f(r2 * cos(angle + 2.0f * da), r2 * sin(angle + 2.0f * da), width * 0.5f);
			glVertex3f(r1 * cos(angle + 3.0f * da), r1 * sin(angle + 3.0f * da), width * 0.5f);
		}
		glEnd();

		/* draw back face */
		glBegin(GL_QUAD_STRIP);
		for ( i = 0; i <= teeth; i++ ) {
			angle = i * 2.0f * (float)Math.PI / teeth;
			glVertex3f(r1 * cos(angle), r1 * sin(angle), -width * 0.5f);
			glVertex3f(r0 * cos(angle), r0 * sin(angle), -width * 0.5f);
			glVertex3f(r1 * cos(angle + 3 * da), r1 * sin(angle + 3 * da), -width * 0.5f);
			glVertex3f(r0 * cos(angle), r0 * sin(angle), -width * 0.5f);
		}
		glEnd();

		/* draw back sides of teeth */
		glBegin(GL_QUADS);
		for ( i = 0; i < teeth; i++ ) {
			angle = i * 2.0f * (float)Math.PI / teeth;
			glVertex3f(r1 * cos(angle + 3 * da), r1 * sin(angle + 3 * da), -width * 0.5f);
			glVertex3f(r2 * cos(angle + 2 * da), r2 * sin(angle + 2 * da), -width * 0.5f);
			glVertex3f(r2 * cos(angle + da), r2 * sin(angle + da), -width * 0.5f);
			glVertex3f(r1 * cos(angle), r1 * sin(angle), -width * 0.5f);
		}
		glEnd();

		/* draw outward faces of teeth */
		glBegin(GL_QUAD_STRIP);
		for ( i = 0; i < teeth; i++ ) {
			angle = i * 2.0f * (float)Math.PI / teeth;
			glVertex3f(r1 * cos(angle), r1 * sin(angle), width * 0.5f);
			glVertex3f(r1 * cos(angle), r1 * sin(angle), -width * 0.5f);
			u = r2 * cos(angle + da) - r1 * cos(angle);
			v = r2 * sin(angle + da) - r1 * sin(angle);
			len = (float)Math.sqrt(u * u + v * v);
			u /= len;
			v /= len;
			glNormal3f(v, -u, 0.0f);
			glVertex3f(r2 * cos(angle + da), r2 * sin(angle + da), width * 0.5f);
			glVertex3f(r2 * cos(angle + da), r2 * sin(angle + da), -width * 0.5f);
			glNormal3f(cos(angle), sin(angle), 0.0f);
			glVertex3f(r2 * cos(angle + 2 * da), r2 * sin(angle + 2 * da), width * 0.5f);
			glVertex3f(r2 * cos(angle + 2 * da), r2 * sin(angle + 2 * da), -width * 0.5f);
			u = r1 * cos(angle + 3 * da) - r2 * cos(angle + 2 * da);
			v = r1 * sin(angle + 3 * da) - r2 * sin(angle + 2 * da);
			glNormal3f(v, -u, 0.0f);
			glVertex3f(r1 * cos(angle + 3 * da), r1 * sin(angle + 3 * da), width * 0.5f);
			glVertex3f(r1 * cos(angle + 3 * da), r1 * sin(angle + 3 * da), -width * 0.5f);
			glNormal3f(cos(angle), sin(angle), 0.0f);
		}
		glVertex3f(r1 * cos(0), r1 * sin(0), width * 0.5f);
		glVertex3f(r1 * cos(0), r1 * sin(0), -width * 0.5f);
		glEnd();

		glShadeModel(GL_SMOOTH);

		/* draw inside radius cylinder */
		glBegin(GL_QUAD_STRIP);
		for ( i = 0; i <= teeth; i++ ) {
			angle = i * 2.0f * (float)Math.PI / teeth;
			glNormal3f(-cos(angle), -sin(angle), 0.0f);
			glVertex3f(r0 * cos(angle), r0 * sin(angle), -width * 0.5f);
			glVertex3f(r0 * cos(angle), r0 * sin(angle), width * 0.5f);
		}
		glEnd();
	}

}