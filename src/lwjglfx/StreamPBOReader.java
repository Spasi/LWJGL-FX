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

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

import static org.lwjgl.opengl.EXTFramebufferBlit.*;
import static org.lwjgl.opengl.EXTFramebufferMultisample.*;
import static org.lwjgl.opengl.EXTFramebufferObject.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL32.*;

/** Implements streaming PBO updates from a framebuffer object. */
public class StreamPBOReader extends StreamPBO {

	private final ReadHandler handler;

	private final FBOUtil fboUtil;
	private final int     renderFBO;

	private final boolean USE_COPY_BUFFER_SUB_DATA;

	private int samples;

	private int rgbaBuffer;
	private int depthBuffer;

	private int msaaResolveFBO;
	private int msaaResolveBuffer;

	private int synchronousFrames;

	public StreamPBOReader(final ReadHandler handler) {
		this(handler, 1);
	}

	public StreamPBOReader(final ReadHandler handler, final int samples) {
		this(handler, samples, 2);
	}

	public StreamPBOReader(final ReadHandler handler, final int samples, final int framesToBuffer) {
		super(framesToBuffer);

		final ContextCapabilities caps = GLContext.getCapabilities();

		if ( !(caps.OpenGL30 || caps.GL_EXT_framebuffer_object) )
			throw new UnsupportedOperationException("Framebuffer object support is required.");

		this.handler = handler;

		fboUtil = getFBOUtil(caps);
		renderFBO = fboUtil.genFramebuffers();

		USE_COPY_BUFFER_SUB_DATA = caps.OpenGL31 || caps.GL_ARB_copy_buffer;

		this.samples = checkSamples(samples, caps);
		resize(handler.getWidth(), handler.getHeight());
	}

	private static int checkSamples(final int samples, final ContextCapabilities caps) {
		if ( samples <= 1 )
			return samples;

		if ( !(caps.OpenGL30 || (caps.GL_EXT_framebuffer_multisample && caps.GL_EXT_framebuffer_blit)) )
			throw new UnsupportedOperationException("Multisampled rendering on framebuffer objects is not supported.");

		return Math.min(samples, glGetInteger(GL_MAX_SAMPLES));
	}

	public ReadHandler getHandler() {
		return handler;
	}

	private void resize(final int width, final int height) {
		if ( width < 0 || height < 0 )
			throw new IllegalArgumentException("Invalid dimensions: " + width + " x " + height);

		destroyRoundRobinObjects();

		this.width = width;
		this.height = height;

		if ( width == 0 || height == 0 )
			return;

		bufferIndex = synchronousFrames = transfersToBuffer - 1;

		// Setup render FBO

		fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, renderFBO);

		if ( rgbaBuffer != 0 ) fboUtil.deleteRenderbuffers(rgbaBuffer);
		if ( depthBuffer != 0 ) fboUtil.deleteRenderbuffers(depthBuffer);

		rgbaBuffer = fboUtil.genRenderbuffers();
		fboUtil.bindRenderbuffer(GL_RENDERBUFFER, rgbaBuffer);
		if ( samples <= 1 )
			fboUtil.renderbufferStorage(GL_RENDERBUFFER, GL_RGBA, width, height);
		else
			fboUtil.renderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height);
		fboUtil.framebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rgbaBuffer);

		depthBuffer = fboUtil.genRenderbuffers();
		fboUtil.bindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
		if ( samples <= 1 )
			fboUtil.renderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
		else
			fboUtil.renderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH24_STENCIL8, width, height);
		fboUtil.framebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);

		glViewport(0, 0, width, height);

		fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);

		if ( 1 < samples ) {
			// Setup MSAA resolve FBO

			if ( msaaResolveFBO == 0 ) msaaResolveFBO = fboUtil.genFramebuffers();

			fboUtil.bindFramebuffer(GL_READ_FRAMEBUFFER, msaaResolveFBO);

			if ( msaaResolveBuffer != 0 ) fboUtil.deleteRenderbuffers(msaaResolveBuffer);

			msaaResolveBuffer = fboUtil.genRenderbuffers();
			fboUtil.bindRenderbuffer(GL_RENDERBUFFER, msaaResolveBuffer);
			fboUtil.renderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
			fboUtil.framebufferRenderbuffer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaResolveBuffer);

			fboUtil.bindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		} else if ( msaaResolveFBO != 0 ) {
			if ( msaaResolveBuffer != 0 ) fboUtil.deleteRenderbuffers(msaaResolveBuffer);
			fboUtil.deleteFramebuffers(msaaResolveFBO);
		}

		// Setup read-back buffers

		resizeBuffers(width, height, GL_PIXEL_PACK_BUFFER, GL_STREAM_READ);
	}

	public void bind() {
		if ( this.width != handler.getWidth() || this.height != handler.getHeight() )
			resize(handler.getWidth(), handler.getHeight());

		fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, renderFBO);
	}

	public void nextFrame() {
		if ( width == 0 || height == 0 )
			return;

		if ( msaaResolveFBO == 0 ) {
			fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
			fboUtil.bindFramebuffer(GL_READ_FRAMEBUFFER, renderFBO);
		} else {
			// Resolve MSAA
			fboUtil.bindFramebuffer(GL_READ_FRAMEBUFFER, renderFBO);
			fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, msaaResolveFBO);
			fboUtil.blitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
			fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
			fboUtil.bindFramebuffer(GL_READ_FRAMEBUFFER, msaaResolveFBO);
		}

		final int trgPBO = (int)(bufferIndex % transfersToBuffer);
		final int srcPBO = (int)((bufferIndex + 1) % transfersToBuffer);

		glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[trgPBO]);

		// Back-pressure. Make sure we never buffer more than <transfersToBuffer> frames ahead.

		if ( latches[trgPBO] != null )
			waitOnLatch(trgPBO);

		// Asynchronously transfer current frame

		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		if ( USE_AMD_PINNED_MEMORY )
			fences[trgPBO] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

		fboUtil.bindFramebuffer(GL_READ_FRAMEBUFFER, 0);

		// This will be non-zero for the first (transfersToBuffer - 1) frames
		// after start-up or a resize.
		if ( 0 < synchronousFrames ) {
			// The srcPBO is currently empty. Wait for trgPBO's ReadPixels to complete and copy the current frame to srcPBO.
			// We do this to avoid sending an empty buffer for processing, which would cause a visible flicker on resize.
			copyFrames(trgPBO, srcPBO);
			synchronousFrames--;
		}

		// Time to process the srcPBO

		final ByteBuffer pinnedBuffer;
		if ( USE_AMD_PINNED_MEMORY ) {
			if ( fences[srcPBO] != null ) // Wait for ReadPixels on the srcPBO to complete
				waitOnFence(srcPBO);

			// Just return the srcPBO buffer
			pinnedBuffer = pinnedBuffers[srcPBO];
		} else {
			glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[srcPBO]);
			// The buffer will be unmapped in waitOnLatch
			pinnedBuffer = (pinnedBuffers[srcPBO] = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, width * height * 4, pinnedBuffers[srcPBO]));
		}

		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

		// Send the buffer for processing

		handler.process(
			width, height,
			pinnedBuffer,
			latches[srcPBO] = new CountDownLatch(1)
		);

		bufferIndex++;
	}

	private void copyFrames(final int src, final int trg) {
		if ( USE_AMD_PINNED_MEMORY ) {
			waitOnFence(src);

			final ByteBuffer srcBuffer = pinnedBuffers[src];
			final ByteBuffer trgBuffer = pinnedBuffers[trg];

			final int srcPos = srcBuffer.position();
			final int trgPos = trgBuffer.position();

			trgBuffer.put(srcBuffer);

			srcBuffer.position(srcPos);
			trgBuffer.position(trgPos);
		} else if ( USE_COPY_BUFFER_SUB_DATA ) {
			glBindBuffer(GL_COPY_READ_BUFFER, pbos[src]);
			glBindBuffer(GL_COPY_WRITE_BUFFER, pbos[trg]);

			glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, width * height * 4);

			glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
			glBindBuffer(GL_COPY_READ_BUFFER, 0);
		} else {
			pinnedBuffers[src] = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, width * height * 4, pinnedBuffers[src]);

			glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[trg]);
			glBufferSubData(GL_PIXEL_PACK_BUFFER, 0, pinnedBuffers[src]);

			glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[src]);
			glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
		}
	}

	protected void waitOnLatch(final int index) {
		try {
			latches[index].await();
			latches[index] = null;
			if ( !USE_AMD_PINNED_MEMORY )
				glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void destroyRoundRobinObjects() {
		for ( int i = 0; i < latches.length; i++ ) {
			if ( latches[i] != null ) {
				if ( !USE_AMD_PINNED_MEMORY )
					glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[i]);

				waitOnLatch(i);
			}
		}

		glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);

		for ( int i = 0; i < pbos.length; i++ ) {
			if ( pbos[i] != 0 )
				glDeleteBuffers(pbos[i]);
		}

		if ( fences == null )
			return;

		for ( int i = 0; i < fences.length; i++ ) {
			if ( fences[i] != null )
				waitOnFence(i);
		}
	}

	public void destroy() {
		destroyRoundRobinObjects();

		fboUtil.bindFramebuffer(GL_DRAW_FRAMEBUFFER, renderFBO);

		if ( rgbaBuffer != 0 ) fboUtil.deleteRenderbuffers(rgbaBuffer);
		if ( depthBuffer != 0 ) fboUtil.deleteRenderbuffers(depthBuffer);

		fboUtil.deleteFramebuffers(renderFBO);

		if ( msaaResolveBuffer != 0 ) fboUtil.deleteRenderbuffers(msaaResolveBuffer);
		if ( msaaResolveFBO != 0 ) fboUtil.deleteFramebuffers(msaaResolveFBO);
	}

	public interface ReadHandler {

		int getWidth();

		int getHeight();

		void process(final int width, final int height, ByteBuffer data, CountDownLatch signal);

	}

	interface FBOUtil {

		int genFramebuffers();

		void bindFramebuffer(int target, int framebuffer);

		void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer);

		void deleteFramebuffers(int framebuffer);

		int genRenderbuffers();

		void bindRenderbuffer(int target, int renderbuffer);

		void renderbufferStorage(int target, int internalformat, int width, int height);

		void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height);

		void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);

		void deleteRenderbuffers(int renderbuffer);

	}

	private static FBOUtil getFBOUtil(final ContextCapabilities caps) {
		if ( caps.OpenGL30 )
			return new FBOUtil() {
				public int genFramebuffers() {
					return glGenFramebuffers();
				}

				public void bindFramebuffer(int target, int framebuffer) {
					glBindFramebuffer(target, framebuffer);
				}

				public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
					glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
				}

				public void deleteFramebuffers(int framebuffer) {
					glDeleteFramebuffers(framebuffer);
				}

				public int genRenderbuffers() {
					return glGenRenderbuffers();
				}

				public void bindRenderbuffer(int target, int renderbuffer) {
					glBindRenderbuffer(target, renderbuffer);
				}

				public void renderbufferStorage(int target, int internalformat, int width, int height) {
					glRenderbufferStorage(target, internalformat, width, height);
				}

				public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
					glRenderbufferStorageMultisample(target, samples, internalformat, width, height);
				}

				public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
					glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
				}

				public void deleteRenderbuffers(int renderbuffer) {
					glDeleteRenderbuffers(renderbuffer);
				}
			};
		else if ( caps.GL_EXT_framebuffer_object )
			return new FBOUtil() {
				public int genFramebuffers() {
					return glGenFramebuffersEXT();
				}

				public void bindFramebuffer(int target, int framebuffer) {
					glBindFramebufferEXT(target, framebuffer);
				}

				public void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
					glFramebufferRenderbufferEXT(target, attachment, renderbuffertarget, renderbuffer);
				}

				public void deleteFramebuffers(int framebuffer) {
					glDeleteFramebuffersEXT(framebuffer);
				}

				public int genRenderbuffers() {
					return glGenRenderbuffersEXT();
				}

				public void bindRenderbuffer(int target, int renderbuffer) {
					glBindRenderbufferEXT(target, renderbuffer);
				}

				public void renderbufferStorage(int target, int internalformat, int width, int height) {
					glRenderbufferStorageEXT(target, internalformat, width, height);
				}

				public void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
					glRenderbufferStorageMultisampleEXT(target, samples, internalformat, width, height);
				}

				public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
					glBlitFramebufferEXT(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
				}

				public void deleteRenderbuffers(int renderbuffer) {
					glDeleteRenderbuffersEXT(renderbuffer);
				}
			};
		else
			throw new UnsupportedOperationException("Framebuffer object is not available.");
	}

}