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

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL32.*;

/** Implements streaming PBO updates to an OpenGL texture. */
public class StreamPBOWriter extends StreamPBO {

	private final WriteHandler handler;

	private final int texID;

	private long currentIndex;

	private boolean resetTexture;

	public StreamPBOWriter(final WriteHandler handler) {
		this(handler, 2);
	}

	public StreamPBOWriter(final WriteHandler handler, final int updatesToBuffer) {
		super(updatesToBuffer);

		this.handler = handler;

		texID = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texID);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	public WriteHandler getHandler() {
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

		bufferIndex = 0;
		currentIndex = 0;

		resetTexture = true;

		// Setup upload buffers

		resizeBuffers(width, height, GL_PIXEL_UNPACK_BUFFER, GL_STREAM_DRAW);
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public void nextFrame() {
		if ( width != handler.getWidth() || height != handler.getHeight() )
			resize(handler.getWidth(), handler.getHeight());

		if ( width == 0 || height == 0 )
			return;

		final int trgPBO = (int)(bufferIndex % transfersToBuffer);

		// Back-pressure. Make sure we never buffer more than <transfersToBuffer> frames ahead.

		if ( mappingState.get(trgPBO) ) {
			waitForProcessingToComplete(trgPBO);
			upload(trgPBO);
		}

		final ByteBuffer pinnedBuffer;
		if ( USE_AMD_PINNED_MEMORY ) {
			if ( fences[trgPBO] != null ) // Wait for TexSubImage from the trgPBO to complete
				waitOnFence(trgPBO);

			// Just return the trgPBO buffer
			pinnedBuffer = pinnedBuffers[trgPBO];
		} else {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbos[trgPBO]);
			// We don't need to manually synchronized here, MapBuffer will block until TexSubImage2D has finished.
			// The buffer will be unmapped in waitForProcessingToComplete
			pinnedBuffer = (pinnedBuffers[trgPBO] = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY, width * height * 4, pinnedBuffers[trgPBO]));
		}

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

		// Send the buffer for processing

		mappingState.set(trgPBO, true);
		semaphores[trgPBO].acquireUninterruptibly();

		handler.process(
			width, height,
			pinnedBuffer,
			semaphores[trgPBO]
		);

		bufferIndex++;
	}

	private void upload(final int srcPBO) {
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbos[srcPBO]);

		if ( !USE_AMD_PINNED_MEMORY )
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);

		mappingState.set(srcPBO, false);

		// Asynchronously upload current update

		if ( resetTexture ) {
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			resetTexture = false;
		} else
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, 0);

		if ( USE_AMD_PINNED_MEMORY )
			fences[srcPBO] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

		currentIndex++;
	}

	public void bind() {
		glBindTexture(GL_TEXTURE_2D, texID);

		final int srcPBO = (int)(currentIndex % transfersToBuffer);
		if ( !mappingState.get(srcPBO) )
			return;

		if ( resetTexture ) // Synchronize to show the first frame immediately
			waitForProcessingToComplete(srcPBO);
		else if ( semaphores[srcPBO].tryAcquire() ) // Non-blocking
			semaphores[srcPBO].release(); // Give it back
		else // Give-up, try again next frame
			return;

		upload(srcPBO);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
	}

	private void destroyRoundRobinObjects() {
		for ( int i = 0; i < semaphores.length; i++ ) {
			waitForProcessingToComplete(i);

			if ( !USE_AMD_PINNED_MEMORY && mappingState.get(i) ) {
				glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbos[i]);
				glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
				mappingState.set(i, false);
			}
		}

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

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
	}

	public interface WriteHandler {

		int getWidth();

		int getHeight();

		void process(final int width, final int height, ByteBuffer buffer, Semaphore signal);

	}

}